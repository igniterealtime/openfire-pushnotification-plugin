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
import org.dom4j.io.SAXReader;
import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.openfire.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class PushServiceManager
{
    public static final Logger Log = LoggerFactory.getLogger( PushServiceManager.class );

    public static void register( final User user, final JID pushService, final String node, final Element publishOptions ) throws SQLException
    {
        Log.debug( "Registering user '{}' to node '{}' of service '{}'.", new Object[] { user.getUsername(), node, pushService.toString() } );

        Connection connection = null;
        PreparedStatement pstmt = null;
        try
        {
            connection = DbConnectionManager.getConnection();
            pstmt = connection.prepareStatement( "INSERT INTO ofPushNotiService (username, service, node, options) VALUES(?,?,?,?) " );
            pstmt.setString( 1, user.getUsername() );
            pstmt.setString( 2, pushService.toString() );
            pstmt.setString( 3, node );
            pstmt.setString( 4, publishOptions == null ? null : publishOptions.asXML() );
            pstmt.execute();
        }
        finally
        {
            DbConnectionManager.closeConnection( null, pstmt, connection );
        }
    }

    public static void deregister( final User user ) throws SQLException
    {
        if ( user == null ) {
            throw new IllegalArgumentException( "Argument 'user' cannot be null." );
        }

        Log.debug( "Deregistered user '{}' from all services.", user.getUsername() );

        Connection connection = null;
        PreparedStatement pstmt = null;
        try
        {
            connection = DbConnectionManager.getConnection();
            pstmt = connection.prepareStatement( "DELETE FROM ofPushNotiService WHERE username = ?" );
            pstmt.setString( 1, user.getUsername() );
            pstmt.execute();
        }
        finally
        {
            DbConnectionManager.closeConnection( null, pstmt, connection );
        }
    }

    public static void deregister( final User user, final JID pushService ) throws SQLException
    {
        if ( pushService == null )
        {
            deregister( user );
            return;
        }

        Log.debug( "Deregistered user '{}' from all nodes of service '{}'.", user.getUsername(), pushService.toString() );

        Connection connection = null;
        PreparedStatement pstmt = null;
        try
        {
            connection = DbConnectionManager.getConnection();
            pstmt = connection.prepareStatement( "DELETE FROM ofPushNotiService WHERE username = ? AND service = ?" );
            pstmt.setString( 1, user.getUsername() );
            pstmt.setString( 2, pushService.toString() );
            pstmt.execute();
        }
        finally
        {
            DbConnectionManager.closeConnection( null, pstmt, connection );
        }
    }

    public static void deregister( final User user, final JID pushService, final String node ) throws SQLException
    {
        if ( node == null )
        {
            deregister( user, pushService );
            return;
        }

        Log.debug( "Deregistering user '{}' from node '{}' of service '{}'.", new Object[] { user.getUsername(), node, pushService.toString() } );

        Connection connection = null;
        PreparedStatement pstmt = null;
        try
        {
            connection = DbConnectionManager.getConnection();
            pstmt = connection.prepareStatement( "DELETE FROM ofPushNotiService WHERE username = ? AND service = ? AND node = ?" );
            pstmt.setString( 1, user.getUsername() );
            pstmt.setString( 2, pushService.toString() );
            pstmt.setString( 3, node );
            pstmt.execute();
        }
        finally
        {
            DbConnectionManager.closeConnection( null, pstmt, connection );
        }
    }

    public static Map<JID,Map<String, Element>> getServiceNodes( final User user ) throws SQLException
    {
        final Map<JID, Map<String, Element>> result = new HashMap<>();

        Connection connection = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try
        {
            connection = DbConnectionManager.getConnection();
            pstmt = connection.prepareStatement( "SELECT service, node, options FROM ofPushNotiService WHERE username = ?" );
            pstmt.setString( 1, user.getUsername() );
            rs = pstmt.executeQuery();
            while ( rs.next() )
            {
                try
                {
                    final String service = rs.getString( "service" );
                    final String node = rs.getString( "node" );
                    final String options = rs.getString( "options" );

                    final JID serviceJID = new JID( service );
                    final Map<String, Element> serviceConfig = result.getOrDefault( serviceJID, new HashMap<>() );

                    final Element optionsElement;
                    if (options != null) {
                        optionsElement = new SAXReader().read(new StringReader(options)).getRootElement();
                        optionsElement.detach();
                    } else {
                        optionsElement = null;
                    }


                    serviceConfig.put( node, optionsElement );
                    result.put( serviceJID, serviceConfig );
                }
                catch ( Exception e )
                {
                    Log.warn( "Unable to process database row content while obtaining push service configuration for user '{}'.", user.toString(), e );
                }
            }
        }
        finally
        {
            DbConnectionManager.closeConnection( rs, pstmt, connection );
        }

        Log.trace( "User '{}' has {} push notification services configured.", user, result.size());
        return result;
    }
}
