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
import org.jivesoftware.openfire.OfflineMessageListener;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.session.ClientSession;
import org.jivesoftware.openfire.session.Session;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.forms.DataForm;
import org.xmpp.forms.FormField;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;

import java.util.Map;

public class PushInterceptor implements PacketInterceptor, OfflineMessageListener
{
    private static final Logger Log = LoggerFactory.getLogger( PushInterceptor.class );

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
        }
        catch ( Exception e )
        {
            Log.warn( "An exception occurred while obtain push notification service nodes for user '{}'. If the user has push notifications enabled, these have not been sent.", user.toString(), e );
            return;
        }

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
    public void messageStored( final Message message )
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
}
