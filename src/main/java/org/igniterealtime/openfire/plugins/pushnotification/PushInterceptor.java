/*
 * Copyright (C) 2019 Ignite Realtime Foundation. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.igniterealtime.openfire.plugins.pushnotification;

import org.dom4j.Element;
import org.dom4j.QName;
import org.jivesoftware.openfire.OfflineMessage;
import org.jivesoftware.openfire.OfflineMessageListener;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.cache.Cache;
import org.jivesoftware.util.cache.CacheFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.forms.DataForm;
import org.xmpp.forms.FormField;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.Lock;

public class PushInterceptor implements PacketInterceptor, OfflineMessageListener
{
    private static final Logger Log = LoggerFactory.getLogger( PushInterceptor.class );

    /**
     * An memory-only cache that keeps track of for which messages (by ID) push notifications have been generated for
     * specific users (by username). Key: username. Value: Set of message identifiers.
     */
    // Note: it's MUCH MORE convenient to use a single Cache that uses a custom class to keep track of notifications per user. However
    //       that will cause to ClassCastExceptions when the plugin gets reloaded while the cache contains (or contained) instances of
    //       that class! See https://github.com/igniterealtime/openfire-pushnotification-plugin/issues/19
    //       To prevent this issue, cache entries should only contain classes loaded by Openfire's classloader (and explicitly not classes
    //       loaded by the classloader that's used by this plugin). In other words: do not use classes provided by the plugin itself.
    private static final Cache<String, HashSet<String>> MESSAGES_BY_USER = CacheFactory.createCache( "pushnotification.users" );

    /**
     * An memory-only cache that keeps track of when a push notification was generated for a particular message.
     * Key: message identifier. Value: Set of instant.
     */
    // Note: it's MUCH MORE convenient to use a single Cache that uses a custom class to keep track of notifications per user. However
    //       that will cause to ClassCastExceptions when the plugin gets reloaded while the cache contains (or contained) instances of
    //       that class! See https://github.com/igniterealtime/openfire-pushnotification-plugin/issues/19
    //       To prevent this issue, cache entries should only contain classes loaded by Openfire's classloader (and explicitly not classes
    //       loaded by the classloader that's used by this plugin). In other words: do not use classes provided by the plugin itself.
    private static final Cache<String, HashSet<Instant>> INSTANTS_BY_MESSAGE = CacheFactory.createCache( "pushnotification.messages" );

    /**
     * Invokes the interceptor on the specified packet. The interceptor can either modify
     * the packet, or throw a PacketRejectedException to block it from being sent or processed
     * (when read).<p>
     * <p>
     * An exception can only be thrown when <tt>processed</tt> is false which means that the read
     * packet has not been processed yet or the packet was not sent yet. If the exception is thrown
     * with a "read" packet then the sender of the packet will receive an answer with an error. But
     * if the exception is thrown with a "sent" packet then nothing will happen.<p>
     * <p>
     * Note that for each packet, every interceptor will be called twice: once before processing
     * is complete (<tt>processing==true</tt>) and once after processing is complete. Typically,
     * an interceptor will want to ignore one or the other case.
     *
     * @param packet    the packet to take action on.
     * @param session   the session that received or is sending the packet.
     * @param incoming  flag that indicates if the packet was read by the server or sent from
     *                  the server.
     * @param processed flag that indicates if the action (read/send) was performed. (PRE vs. POST).
     * @throws PacketRejectedException if the packet should be prevented from being processed.
     */
    @Override
    public void interceptPacket( final Packet packet, final Session session, final boolean incoming, final boolean processed ) throws PacketRejectedException
    {
        if ( incoming ) {
            return;
        }

        if ( !processed ) {
            return;
        }

        if ( !(packet instanceof Message)) {
            return;
        }

        final String body = ((Message) packet).getBody();
        if ( body == null || body.isEmpty() )
        {
            return;
        }

        if (!(session instanceof ClientSession)) {
            return;
        }

        if (((ClientSession) session).isAnonymousUser()) {
            return;
        }

        final User user;
        String username = null;
        try
        {
            username = ((ClientSession) session).getUsername();
            user = XMPPServer.getInstance().getUserManager().getUser( username );
        }
        catch ( UserNotFoundException e )
        {
            Log.debug( "Not a recognized user: " + username, e );
            return;
        }

        Log.trace( "If user '{}' has push services configured, pushes need to be sent for a message that just arrived.", user );
        tryPushNotification( user, (Message) packet );
    }

    private void tryPushNotification( User user, Message message )
    {
        final Map<JID, Map<String, Element>> serviceNodes;
        try
        {
            serviceNodes = PushServiceManager.getServiceNodes( user );
            Log.trace( "For user '{}', {} push service(s) are configured.", user.toString(), serviceNodes.size() );
            if (serviceNodes.isEmpty()) {
                return;
            }
        }
        catch ( Exception e )
        {
            Log.warn( "An exception occurred while obtain push notification service nodes for user '{}'. If the user has push notifications enabled, these have not been sent.", user.toString(), e );
            return;
        }

        // Basic throttling.
        final Lock lock = CacheFactory.getLock(user.getUsername(), MESSAGES_BY_USER);
        lock.lock();
        try {
            if ( wasPushAttemptedFor( user, message, Duration.ofMinutes(5)) ) {
                Log.debug( "For user '{}', not re-attempting push for this message that already had a push attempt recently.", user.toString() );
                return;
            }

            if ( attemptsForLast(user, Duration.ofSeconds(1)) > JiveGlobals.getIntProperty( "pushnotifications.max-per-second", 5 ) ) {
                Log.debug( "For user '{}', skipping push, as user is over the rate limit of 5 push attempts per second.", user.toString() );
                return;
            }

            addAttemptFor( user, message );
        } finally {
            lock.unlock();
        }

        // Perform the pushes
        for ( final Map.Entry<JID, Map<String, Element>> serviceNode : serviceNodes.entrySet() )
        {
            final JID service = serviceNode.getKey();
            Log.trace( "For user '{}', found service '{}'", user.toString(), service );

            final Map<String, Element> nodes = serviceNode.getValue();
            for ( final Map.Entry<String, Element> nodeConfig : nodes.entrySet() )
            {
                final String node = nodeConfig.getKey();
                final Element publishOptions = nodeConfig.getValue();

                Log.trace( "For user '{}', found node '{}' of service '{}'", new Object[] { user.toString(), node, service });
                final IQ push = new IQ( IQ.Type.set );
                push.setTo( service );
                push.setFrom( XMPPServer.getInstance().getServerInfo().getXMPPDomain() );
                push.setChildElement( "pubsub", "http://jabber.org/protocol/pubsub" );
                final Element publish = push.getChildElement().addElement( "publish" );
                publish.addAttribute( "node", node );
                final Element item = publish.addElement( "item" );

                final Element notification = item.addElement( QName.get( "notification", "urn:xmpp:push:0" ) );
                if ( JiveGlobals.getBooleanProperty( "pushnotifications.summary.enable", true ) )
                {
                    final DataForm notificationForm = new DataForm(DataForm.Type.form);
                    notificationForm.addField("FORM_TYPE", null, FormField.Type.hidden).addValue("urn:xmpp:push:summary");
                    notificationForm.addField("message-count", null, FormField.Type.text_single).addValue(1);
                    final FormField lastSenderField = notificationForm.addField("last-message-sender", null, FormField.Type.text_single);
                    if ( JiveGlobals.getBooleanProperty( "pushnotifications.summary.include-last-sender", false ) ) {
                        lastSenderField.addValue( message.getFrom() );
                    }
                    final FormField lastMessageField = notificationForm.addField("last-message-body", null, FormField.Type.text_single);
                    String includedBody = "New Message"; // For IOS to wake up, some kind of content is required.
                    if ( JiveGlobals.getBooleanProperty( "pushnotifications.summary.include-last-message-body", false ) ) {
                        if ( message.getBody() != null && !message.getBody().trim().isEmpty() ) {
                            includedBody = message.getBody().trim();
                        }
                    }
                    lastMessageField.addValue( includedBody );
                    notification.add(notificationForm.getElement());
                }

                if ( publishOptions != null )
                {
                    Log.trace( "For user '{}', found publish options for node '{}' of service '{}'", new Object[] { user.toString(), node, service });
                    final Element pubOptEl = push.getChildElement().addElement( "publish-options" );
                    pubOptEl.add( publishOptions );
                }
                try
                {
                    Log.trace( "For user '{}', Routing push notification to '{}'", user.toString(), push.getTo() );
                    XMPPServer.getInstance().getRoutingTable().routePacket( push.getTo(), push, true );
                } catch ( Exception e ) {
                    Log.warn( "An exception occurred while trying to deliver a notification for user '{}' to node '{}' on service '{}'.", new Object[] { user, node, service, e } );
                }

                Log.debug( "Delivered a notification for user '{}' to node '{}' on service '{}'.", new Object[] { user, node, service } );
            }
        }
    }

    /**
     * Notification message indicating that a message was not stored offline but bounced
     * back to the sender.
     *
     * @param message the message that was bounced.
     */
    @Override
    public void messageBounced( final Message message )
    {}

    /**
     * Notification message indicating that a message was stored offline since the target entity
     * was not online at the moment.
     *
     * @param message the message that was stored offline.
     */
    @Override
    public void messageStored( final OfflineMessage message )
    {
        if ( message.getBody() == null || message.getBody().isEmpty() )
        {
            return;
        }

        Log.trace( "Message stored to offline storage. Try to send push notification." );
        final User user;
        try
        {
            user = XMPPServer.getInstance().getUserManager().getUser( message.getTo().getNode() );
            tryPushNotification( user, message );
        }
        catch ( UserNotFoundException e )
        {
            Log.error( "Unable to find local user '{}'.", message.getTo().getNode(), e );
        }
    }

    /**
     * Checks if a push notification was (attempted to be) sent to a particular user, to notify them of a particular
     * message.
     *
     * @param user The user that would have received the push notification
     * @param message The message for which a push notification would have been sent
     * @param duration The past amount of time in which to check for sent push notifications
     * @return true when at least one push attempt for the user/message was recently sent.
     */
    public boolean wasPushAttemptedFor( final User user, final Message message, final Duration duration )
    {
        final String identifier = getMessageIdentifier(user, message);

        final Lock lock = CacheFactory.getLock(user.getUsername(), MESSAGES_BY_USER);
        lock.lock();
        try {
            /* This can be short-circuited, as the same identifier is used in the secondary cache.
            final HashSet<String> messageIdentifiers = MESSAGES_BY_USER.get( user.getUsername() );
            if ( messageIdentifiers == null || messageIdentifiers.isEmpty() || !messageIdentifiers.contains(identifier)) {
                return false;
            }
            */

            // Look up the timestamps when a push was sent for this particular user/message combinations.
            final HashSet<Instant> sentTimestamps = INSTANTS_BY_MESSAGE.get(identifier);
            if ( sentTimestamps == null || sentTimestamps.isEmpty() ) {
                return false;
            }

            return sentTimestamps.stream().anyMatch( n ->
                n != null && n.isAfter(Instant.now().minus(duration))
            );
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the amount of push notifications (attempted to be) sent to a particular user.
     *
     * @param user The user that would have received the push notification
     * @param duration The past amount of time in which to check for sent push notifications
     * @return The amount of notifications sent to the user recently.
     */
    public long attemptsForLast( final User user, final Duration duration )
    {
        final Lock lock = CacheFactory.getLock(user.getUsername(), MESSAGES_BY_USER);
        lock.lock();
        try {
            final HashSet<String> messageIdentifiers = MESSAGES_BY_USER.get(user.getUsername());
            if (messageIdentifiers == null) {
                return 0;
            }

            // Count the amount of timestamps for each pushed attempt that were within the target duration.
            long result = 0;
            for (final String messageIdentifier : messageIdentifiers) {
                final HashSet<Instant> instants = INSTANTS_BY_MESSAGE.get(messageIdentifier);
                result += instants.stream().filter(instant -> instant.isAfter(Instant.now().minus(duration))).count();
            }

            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Registers attempts to sent a push notification for a particular message to a user.
     *
     * @param user The user that would receive the push notification
     * @param message The message for which a push notification has been sent
     */
    public void addAttemptFor( final User user, final Message message )
    {
        final String identifier = getMessageIdentifier(user, message);

        final Lock lock = CacheFactory.getLock(user.getUsername(), MESSAGES_BY_USER);
        lock.lock();
        try {
            HashSet<String> messageIdentifiers = MESSAGES_BY_USER.get(user.getUsername());
            if (messageIdentifiers == null) {
                messageIdentifiers = new HashSet<>();
            }
            messageIdentifiers.add(identifier);
            // Clustered caches require an explicit PUT for the added element to be registered.
            MESSAGES_BY_USER.put(user.getUsername(), messageIdentifiers);

            HashSet<Instant> instants = INSTANTS_BY_MESSAGE.get(identifier);
            if (instants == null) {
                instants = new HashSet<>();
            }
            instants.add(Instant.now());
            // Clustered caches require an explicit PUT for the added element to be registered.
            INSTANTS_BY_MESSAGE.put(identifier, instants);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove from internal caches all push attempts that were sent before a particular cutoff timestamp.
     *
     * @param cutoff The instant after which all attempts should be retained in the caches.
     */
    public void purgeAllOlderThan(final Instant cutoff)
    {
        Log.debug("Purging cached entries older than {}", cutoff);
        final Set<String> userNames = new HashSet<>(MESSAGES_BY_USER.keySet());

        // Iterate over all message identifiers for each user, to be able to apply the required user-specific lock.
        for ( final String username : userNames )
        {
            final Lock lock = CacheFactory.getLock(username, MESSAGES_BY_USER);
            lock.lock();
            try
            {
                // These are all the message identifiers for this user.
                final HashSet<String> messageIdentifiers = new HashSet<>(MESSAGES_BY_USER.get(username));
                final HashSet<String> removedMessageIds = new HashSet<>();
                for (String messageIdentifier : messageIdentifiers) {
                    // For each message, check when a pushes were sent.
                    final HashSet<Instant> instants = INSTANTS_BY_MESSAGE.get(messageIdentifier);

                    // Remove all entries that can be purged.
                    if (instants.removeIf(i -> i.isBefore(cutoff))) // No need to do anything if nothing changed.
                    {
                        if (instants.isEmpty()) {
                            // When no attempts are left, remove the entry completely.
                            INSTANTS_BY_MESSAGE.remove(messageIdentifier);
                            // Also mark this identifier as being removable from the user set.
                            removedMessageIds.add(messageIdentifier);
                        } else {
                            // When attempts are left, re-add the updated push (an explicit PUT is required for clustered caches).
                            INSTANTS_BY_MESSAGE.put(messageIdentifier, instants);
                        }
                    }
                }

                // When the iteration above caused any messages to be removed, remove them from the user set too.
                if (!removedMessageIds.isEmpty()) {
                    messageIdentifiers.removeAll(removedMessageIds);
                    if (messageIdentifiers.isEmpty()) {
                        // When there are no messages left, remove the entry completely.
                        MESSAGES_BY_USER.remove(username);
                    } else {
                        // When attempts are left, re-add the updated push (an explicit PUT is required for clustered caches).
                        MESSAGES_BY_USER.put(username, messageIdentifiers);
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Generates a reasonably unique identifier for a message / user combination.
     */
    public static String getMessageIdentifier( final User user, final Message message )
    {
        return user.getUsername() + "->" + (message.getID() != null ? message.getID() : "") + message.getFrom().hashCode() + message.getBody().hashCode();
    }
}
