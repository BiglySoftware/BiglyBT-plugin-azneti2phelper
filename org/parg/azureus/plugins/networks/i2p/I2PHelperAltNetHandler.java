/*
 * Created on Nov 14, 2014
 * Created by Paul Gardner
 * 
 * Copyright 2014 Azureus Software, Inc.  All rights reserved.
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or 
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
 */


package org.parg.azureus.plugins.networks.i2p;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import net.i2p.data.Destination;

import com.biglybt.core.util.Debug;
import com.biglybt.core.util.SystemTime;
import org.parg.azureus.plugins.networks.i2p.snarkdht.NID;
import org.parg.azureus.plugins.networks.i2p.snarkdht.NodeInfo;
import org.parg.azureus.plugins.networks.i2p.vuzedht.DHTTransportContactI2P;

import com.biglybt.core.dht.transport.DHTTransportAlternativeContact;
import com.biglybt.core.dht.transport.DHTTransportAlternativeNetwork;
import com.biglybt.core.dht.transport.udp.impl.DHTUDPUtils;

public class 
I2PHelperAltNetHandler 
{
	private DHTTransportAlternativeNetworkImpl		i2p_net = new DHTTransportAlternativeNetworkImpl( DHTTransportAlternativeNetwork.AT_I2P );
	
	protected
	I2PHelperAltNetHandler()
	{
		DHTUDPUtils.registerAlternativeNetwork( i2p_net );
	}
	
	protected void
	contactAlive(
		DHTTransportContactI2P	contact )
	{
		NodeInfo node_info = contact.getNode();
		
		if ( node_info.getDestination() != null && node_info.getNID() != null ){
		
			i2p_net.addContact( node_info );
		}
	}
	
	public NodeInfo
	decodeContact(
		DHTTransportAlternativeContact		contact )
	{
		try{
			Map<String,Object>	map = contact.getProperties();
			
	    	byte[]	nid_bytes 	= (byte[])map.get( "n" );
	    	byte[]	dest_bytes	= (byte[])map.get( "d" );

	    	if ( !map.containsKey( "p" )){
	    		
	    		return( null );
	    	}
	    	
	    	int		port 		= ((Number)map.get( "p" )).intValue();

	    	NID nid = new NID( nid_bytes );
	    	
	    	Destination destination = new Destination();
	    
	    	destination.fromByteArray( dest_bytes );
	    	
	    	NodeInfo ni = new NodeInfo( nid, destination, port );

	    	return( ni );
	    	
		}catch( Throwable e ){
			
			return( null );
		}
	}
	
	protected void
	destroy()
	{
		DHTUDPUtils.unregisterAlternativeNetwork( i2p_net );
	}
	
	private static class
	DHTTransportAlternativeNetworkImpl
		implements DHTTransportAlternativeNetwork
	{
		private static final int ADDRESS_HISTORY_MAX	= 32;
		
		private int	network;
		
		private LinkedList<Object[]>	address_history = new LinkedList<Object[]>();
			
		private
		DHTTransportAlternativeNetworkImpl(
			int			net )
		{
			network	= net;
		}
		
		@Override
		public int
		getNetworkType()
		{
			return( network );
		}
		
		private void
		addContact(
			NodeInfo	node_info )
		{
			synchronized( address_history ){
				
				address_history.addFirst(new Object[]{  node_info, new Long( SystemTime.getMonotonousTime())});
				
				if ( address_history.size() > ADDRESS_HISTORY_MAX ){
					
					address_history.removeLast();
				}
			}
		}
		
		@Override
		public List<DHTTransportAlternativeContact>
		getContacts(
			int		max )
		{	
			List<DHTTransportAlternativeContact> result = new ArrayList<DHTTransportAlternativeContact>( max );
			
			synchronized( address_history ){
				
				for ( Object[] entry: address_history ){
					
					result.add( new DHTTransportAlternativeContactImpl((NodeInfo)entry[0],(Long)entry[1]));
					
					if ( result.size() == max ){
						
						break;
					}
				}
			}
			
			return( result );
		}
		
		private class
		DHTTransportAlternativeContactImpl
			implements DHTTransportAlternativeContact
		{
			private final NodeInfo		node_info;
			private final int	 		seen_secs;
			private final int	 		id;
			
			private
			DHTTransportAlternativeContactImpl(
				NodeInfo		_node_info,
				long			seen )
			{
				node_info	= _node_info;
				
				seen_secs = (int)( seen/1000 );				
			
				int	_id;
				
				try{
				
					_id = Arrays.hashCode( node_info.getDestination().toByteArray());
					
				}catch( Throwable e ){
					
					Debug.out( e );
					
					_id = 0;
				}
				
				id	= _id;
			}
			
			@Override
			public int
			getNetworkType()
			{
				return( network );
			}
			
			@Override
			public int
			getVersion()
			{
				return( 1 );
			}
			
			@Override
			public int
			getID()
			{
				return( id );
			}
			
			@Override
			public int
			getLastAlive()
			{
				return( seen_secs );
			}
			
			@Override
			public int
			getAge()
			{
				return(((int)( SystemTime.getMonotonousTime()/1000)) - seen_secs );
			}
			
			@Override
			public Map<String,Object>
			getProperties()
			{
				Map<String,Object>	properties = new HashMap<String, Object>();
				
				try{
					byte[]		nid	 = node_info.getNID().getData();
					int			port = node_info.getPort();
					byte[]		dest = node_info.getDestination().toByteArray();
					
					properties.put( "n", nid );
					properties.put( "p", port );
					properties.put( "d", dest );	
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
				
				return( properties );
			}
		}
	}
}
