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

import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.jivesoftware.openfire.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.*;

public class PushServiceManager
{
    public static final Logger Log = LoggerFactory.getLogger( PushServiceManager.class );

    public static final String USER_PROPERTY_KEY_PUSH_SERVICES = "push-notification push-services";
    public static final String USER_PROPERTY_KEY_NODE_PREFIX = "push-notification node for service ";
    public static final String USER_PROPERTY_KEY_OPTIONS_PREFIX = "push-notification publish options for node on service ";

    public static void register( final User user, final JID pushService, final String node, final Element publishOptions )
    {
        final String propValue = user.getProperties().get( USER_PROPERTY_KEY_PUSH_SERVICES );
        final Set<JID> services = toJIDSet( propValue );
        services.add( pushService );
        user.getProperties().put( USER_PROPERTY_KEY_PUSH_SERVICES, toCSV( services ) );

        final String propNodeValue = user.getProperties().get( USER_PROPERTY_KEY_NODE_PREFIX + pushService.toString() );
        final Set<String> nodes = toSet( propNodeValue );
        nodes.add( node );
        user.getProperties().put( USER_PROPERTY_KEY_NODE_PREFIX + pushService.toString(), toCSV( nodes ) );

        if ( publishOptions != null )
        {
            user.getProperties().put( USER_PROPERTY_KEY_OPTIONS_PREFIX + pushService.toString() + " " + node, publishOptions.asXML() );
        }
        Log.debug( "Registered node '{}' on service '{}' for user '{}'.", new Object[] { node, pushService.toString(), user.getUsername()} );
    }

    public static void deregister( final User user, final JID pushService, final String node )
    {
        final String propValue = user.getProperties().get( USER_PROPERTY_KEY_PUSH_SERVICES );
        if ( propValue != null )
        {
            final Set<JID> services = toJIDSet( propValue );
            services.remove( pushService );
            if ( services.isEmpty() )
            {
                user.getProperties().remove( USER_PROPERTY_KEY_PUSH_SERVICES );
            }
            else
            {
                user.getProperties().put( USER_PROPERTY_KEY_PUSH_SERVICES, toCSV( services ) );
            }
        }

        final Set<String> removedNodes = new HashSet<>();
        if ( node == null )
        {
            // Remove all nodes for the service.
            final String removed = user.getProperties().remove( USER_PROPERTY_KEY_NODE_PREFIX + pushService.toString() );
            removedNodes.addAll( toSet( removed ) );
        }
        else
        {
            // Remove specific node for the service.
            final String propNodeValue = user.getProperties().get( USER_PROPERTY_KEY_NODE_PREFIX + pushService.toString() );
            if ( propNodeValue != null )
            {
                final Set<String> nodes = toSet( propNodeValue );
                nodes.remove( node );
                if ( nodes.isEmpty() )
                {
                    user.getProperties().remove( USER_PROPERTY_KEY_NODE_PREFIX + pushService.toString() );
                }
                else
                {
                    user.getProperties().put( USER_PROPERTY_KEY_NODE_PREFIX + pushService.toString(), toCSV( nodes ) );
                }
            }
            removedNodes.add( node );
        }

        for ( final String removedNode : removedNodes )
        {
            user.getProperties().remove( USER_PROPERTY_KEY_OPTIONS_PREFIX + pushService.toString() + " " + removedNode );
        }

        Log.debug( "Deregistered {} from service '{}' for user '{}'.", new Object[] { (node == null ? "all nodes" : "node " + node), pushService.toString(), user.getUsername() } );
    }

    static Set<String> toSet( final String values )
    {
        final HashSet<String> result = new HashSet<>();
        if ( values == null || values.isEmpty() ) {
            return result;
        }

        final List<String> splittedValues = Arrays.asList( values.split( "\\s*,\\s*") );
        result.addAll( splittedValues );
        return result;
    }

    static Set<JID> toJIDSet( final String values )
    {
        final Set<JID> result = new HashSet<>();
        for ( final String value : toSet( values ) )
        {
            try
            {
                result.add( new JID( value ) );
            }
            catch ( Exception e )
            {
                Log.warn( "Unable to parse '{}' as a JID!", value, e );
            }
        }
        return result;
    }

    static String toCSV( final Collection<?> values )
    {
        final StringBuilder sb = new StringBuilder();
        final Iterator<?> iter = values.iterator();
        while ( iter.hasNext() ) {
            sb.append( iter.next().toString() );
            if ( iter.hasNext() ) {
                sb.append(',');
            }
        }
        return sb.toString();
    }

    public static Map<JID,Set<String>> getServiceNodes( final User user )
    {
        for ( Map.Entry<String, String> e : user.getProperties().entrySet() )
        {
            Log.debug( "Property for user {}: key {} -> {}", new Object[] { user.toString(), e.getKey(), e.getValue() } );
        }

        final Map<JID, Set<String>> result = new HashMap<>();
        final String propValue = user.getProperties().get( USER_PROPERTY_KEY_PUSH_SERVICES );
        if ( propValue != null )
        {
            final Set<JID> services = toJIDSet( propValue );

            for ( final JID pushService : services )
            {
                final String propNodeValue = user.getProperties().get( USER_PROPERTY_KEY_NODE_PREFIX + pushService.toString() );
                if ( propNodeValue != null )
                {
                    final Set<String> nodes = toSet( propNodeValue );
                    result.put( pushService, nodes );
                }
            }
        }

        return result;
    }

    public static Element getPublishOptions( final User user, final JID pushService, final String node )
    {
        final String result = user.getProperties().get( USER_PROPERTY_KEY_OPTIONS_PREFIX + pushService.toString() + " " + node );
        if ( result == null || result.isEmpty() ) {
            return null;
        }

        try
        {
            final Element rootElement = new SAXReader().read( new StringReader( result ) ).getRootElement();
            rootElement.detach();
            return rootElement;
        }
        catch ( DocumentException e )
        {
            Log.error( "Unable to parse stored publish options for user {}, service {}, node {} into an XML structure.", new Object[] { user, pushService, node, e } );
            return null;
        }
    }
}
