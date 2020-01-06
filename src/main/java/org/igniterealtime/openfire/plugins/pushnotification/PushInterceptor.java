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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import javax.xml.bind.DatatypeConverter;

import com.google.gson.Gson;
import org.apache.http.HttpResponse;
import nl.martijndwars.webpush.*;


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

        final User user;
        try
        {
            user = XMPPServer.getInstance().getUserManager().getUser( ((ClientSession) session).getUsername() );
        }
        catch ( UserNotFoundException e )
        {
            Log.debug( "Not a recognized user.", e );
            return;
        }

        Log.debug( "If user '{}' has push services configured, pushes need to be sent for a message that just arrived.", user );
        tryPushNotification( user, body, packet.getFrom(), ((Message) packet).getType() );
    }

    private void tryPushNotification( User user, String body, JID jid, Message.Type msgtype )
    {
        if (XMPPServer.getInstance().getPresenceManager().isAvailable( user ))
        {
            return; // dont notify if user is online and available. let client handle that
        }

        final Map<JID, Map<String, Element>> serviceNodes;
        try
        {
            serviceNodes = PushServiceManager.getServiceNodes( user );
            Log.debug( "For user '{}', {} push service(s) are configured.", user.toString(), serviceNodes.size() );
        }
        catch ( Exception e )
        {
            Log.warn( "An exception occurred while obtain push notification service nodes for user '{}'. If the user has push notifications enabled, these have not been sent.", user.toString(), e );
            return;
        }

        for ( final Map.Entry<JID, Map<String, Element>> serviceNode : serviceNodes.entrySet() )
        {
            final JID service = serviceNode.getKey();
            final String domain = XMPPServer.getInstance().getServerInfo().getXMPPDomain();

            Log.debug( "For user '{}', found service '{}'", user.toString(), service );

            final Map<String, Element> nodes = serviceNode.getValue();

            for ( final Map.Entry<String, Element> nodeConfig : nodes.entrySet() )
            {
                final String node = nodeConfig.getKey();
                final Element publishOptions = nodeConfig.getValue();

                if (service.getDomain().equals(domain))
                {
                    // when app service domain matches xmmp domain, handle here and assume web push

                    webPush(user, publishOptions, body, jid, msgtype);
                    continue;
                }

                Log.debug( "For user '{}', found node '{}' of service '{}'", new Object[] { user.toString(), node, service });
                final IQ push = new IQ( IQ.Type.set );
                push.setTo( service );
                push.setFrom( domain );
                push.setChildElement( "pubsub", "http://jabber.org/protocol/pubsub" );
                final Element publish = push.getChildElement().addElement( "publish" );
                publish.addAttribute( "node", node );
                final Element item = publish.addElement( "item" );
                item.addElement( QName.get( "notification", "urn:xmpp:push:0" ) );

                if ( publishOptions != null )
                {
                    Log.debug( "For user '{}', found publish options for node '{}' of service '{}'", new Object[] { user.toString(), node, service });
                    final Element pubOptEl = push.getChildElement().addElement( "publish-options" );
                    pubOptEl.add( publishOptions );
                }
                try
                {
                    Log.debug( "For user '{}', Routing push notification to '{}'", user.toString(), push.getTo() );
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

        Log.debug( "Message stored to offline storage. Try to send push notification." );
        final User user;
        try
        {
            user = XMPPServer.getInstance().getUserManager().getUser( message.getTo().getNode() );
            tryPushNotification( user, message.getBody(), message.getFrom(), message.getType() );
        }
        catch ( UserNotFoundException e )
        {
            Log.error( "Unable to find local user '{}'.", message.getTo().getNode(), e );
        }
    }
    /**
     * Push a payload to a subscribed web push user
     *
     *
     * @param user being pushed to.
     * @param publishOptions web push data stored.
     * @param body web push payload.
     */
    private void webPush( final User user, final Element publishOptions, final String body, JID jid, Message.Type msgtype )
    {
        try {
            for (final Element element : publishOptions.elements( "field" ) )
            {
                if ( "secret".equals( element.attributeValue( "var" ) ) )
                {
                    final Element value = element.element( "value" );
                    final byte[] decodedBytes = DatatypeConverter.parseBase64Binary(value.getText());

                    Secret secret = new Gson().fromJson(new String(decodedBytes), Secret.class);

                    Log.debug( "For user '{}', Web push notification keys \npublic - " +  secret.publicKey + "\nprivate - " + secret.privateKey + "\nsubscription - " + secret.subscription.endpoint);

                    PushService pushService = new PushService()
                        .setPublicKey(secret.publicKey)
                        .setPrivateKey(secret.privateKey)
                        .setSubject("xmpp:admin@" + XMPPServer.getInstance().getServerInfo().getXMPPDomain());

                    Stanza stanza = new Stanza(msgtype == Message.Type.chat ? "chat" : "groupchat", jid.asBareJID().toString(), body);
                    Notification notification = new Notification(secret.subscription, (new Gson().toJson(stanza)).toString());
                    HttpResponse response = pushService.send(notification);
                    int statusCode = response.getStatusLine().getStatusCode();

                    String payload = "";

                    Log.debug( "For user '{}', Web push notification response '{}'", user.toString(), response.getStatusLine() );
                }
            }
        } catch (Exception e) {
            Log.warn( "An exception occurred while trying send a web push for user '{}'.", new Object[] { user, e } );
        }
    }
}
