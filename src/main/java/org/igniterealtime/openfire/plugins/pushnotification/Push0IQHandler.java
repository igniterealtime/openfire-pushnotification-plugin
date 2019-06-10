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
import org.jivesoftware.openfire.IQHandlerInfo;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.auth.UnauthorizedException;
import org.jivesoftware.openfire.handler.IQHandler;
import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;

/**
 * An IQ handler implementation for the protocol defined by namespace "urn:xmpp:push:0"
 * in XEP-0357.
 *
 * @author Guus der Kinderen, guus.der.kinderen@gmail.com
 * @see <a href="https://xmpp.org/extensions/xep-0357.html">XEP-0357: "Push Notifications"</a>
 */
public class Push0IQHandler extends IQHandler
{
    private static final Logger Log = LoggerFactory.getLogger( Push0IQHandler.class );

    public static final String ELEMENT_NAME = "enable";
    public static final String ELEMENT_NAMESPACE = "urn:xmpp:push:0";

    public Push0IQHandler()
    {
        super( "Push Notification IQ Handler (enable)." );
    }

    /**
     * Handles the received IQ packet.
     *
     * @param packet the IQ packet to handle.
     * @return the response to send back.
     * @throws UnauthorizedException if the user that sent the packet is not
     *                               authorized to request the given operation.
     */
    @Override
    public IQ handleIQ( final IQ packet ) throws UnauthorizedException
    {
        if ( packet.isResponse() ) {
            Log.trace( "Silently ignoring an unexpected response stanza: {}", packet );
            return null;
        }

        if ( IQ.Type.get.equals( packet.getType() ) )
        {
            Log.trace( "Ignoring an unexpected request stanza of type 'get': {}", packet );
            final IQ result = IQ.createResultIQ( packet );
            result.setError( PacketError.Condition.bad_request );
            return result;
        }

        String action;
        JID pushService;
        String node;
        try
        {
            action = packet.getChildElement().getName();
            pushService = new JID( packet.getChildElement().attributeValue( "jid" ) );
            node = packet.getChildElement().attributeValue( "node" );
        }
        catch ( Exception e )
        {
            Log.debug( "An exception occurred while trying to to parse push service and node from stanza: {}", packet, e );
            action = null;
            pushService = null;
            node = null;
        }

        if ( action == null || pushService == null || (!action.equals( "disable" ) && node == null) )
        {
            Log.trace( "Ignoring a request stanza that could not be parsed: {}", packet );
            final IQ result = IQ.createResultIQ( packet );
            result.setError( PacketError.Condition.bad_request );
            return result;
        }

        if ( !XMPPServer.getInstance().getUserManager().isRegisteredUser( packet.getFrom() ) )
        {
            Log.info( "Denying service for an entity that's not recognized as a registered user: {}", packet.getFrom() );
            throw new UnauthorizedException( "This service is only available to registered, local users." );
        }

        final User user;
        try
        {
            user = XMPPServer.getInstance().getUserManager().getUser( packet.getFrom().getNode() );
        }
        catch ( UserNotFoundException e )
        {
            Log.error( "Unable to load user, while user was confirmed to be a registered user: {}", packet.getFrom() );
            final IQ result = IQ.createResultIQ( packet );
            result.setError( PacketError.Condition.internal_server_error );
            return result;
        }

        Log.trace( "intercepted {}", packet );

        final IQ response;
        switch( action )
        {
            case "enable":
                final Element publishOptions = parsePublishOptions( packet );
                PushServiceManager.register( user, pushService, node, publishOptions );

                Log.debug( "Registered push service '{}', node '{}', for user '{}'.", new Object[]{ pushService.toString(), node, user.getUsername() } );
                response = IQ.createResultIQ( packet );
                break;

            case "disable":
                PushServiceManager.deregister( user, pushService, node );

                Log.debug( "Deregistered push service '{}', node '{}', for user '{}'.", new Object[] { pushService.toString(), node, user.getUsername() } );
                response = IQ.createResultIQ( packet );
                break;

            default:
                Log.debug( "Unknown namespace element: {}", action );
                response = IQ.createResultIQ( packet );
                response.setError( PacketError.Condition.bad_request );
                break;
        }
        return response;
    }

    private Element parsePublishOptions( final IQ packet )
    {
        final Element x = packet.getChildElement().element( QName.get( "x", "jabber:x:data" ) );
        if ( x == null ) {
            return null;
        }

        for (final Element element : x.elements( "field" ) )
        {
            if ( "FORM_TYPE".equals( element.attributeValue( "var" ) ) )
            {
                final Element value = element.element( "value" );
                if ( value != null && "http://jabber.org/protocol/pubsub#publish-options".equals( value.getText() ) )
                {
                    return x;
                }
            }
        }
        return null;
    }

    /**
     * Returns the handler information to help generically handle IQ packets.
     * IQHandlers that aren't local server iq handlers (e.g. chatbots, transports, etc)
     * return <tt>null</tt>.
     *
     * @return The IQHandlerInfo for this handler
     */
    @Override
    public IQHandlerInfo getInfo()
    {
        return new IQHandlerInfo( ELEMENT_NAME, ELEMENT_NAMESPACE );
    }
}
