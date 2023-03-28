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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.biglybt.core.util.Constants;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.SystemTime;

import com.biglybt.core.dht.transport.DHTTransportAlternativeContact;
import com.biglybt.core.dht.transport.DHTTransportAlternativeNetwork;
import com.biglybt.core.dht.transport.udp.impl.DHTUDPUtils;

public class 
I2PHelperAltNetHandlerTor 
{
	private DHTTransportAlternativeNetworkImpl		tor_net = new DHTTransportAlternativeNetworkImpl( 6 ); // replace sometime after 3.4 DHTTransportAlternativeNetwork.AT_TOR );
	
	protected
	I2PHelperAltNetHandlerTor()
	{
		DHTUDPUtils.registerAlternativeNetwork( tor_net );
	}
	
	protected void
	contactAlive(
		InetSocketAddress	contact,
		boolean				is_local )
	{
		tor_net.addContact( contact, is_local );
	}
	
	public InetSocketAddress
	decodeContact(
		DHTTransportAlternativeContact		contact )
	{
		try{
			Map<String,Object>	map = contact.getProperties();
			
	    	byte[]	host_bytes 	= (byte[])map.get( "h" );	    	
	    	int		port 		= ((Number)map.get( "p" )).intValue();

	    	return( InetSocketAddress.createUnresolved( new String( host_bytes, Constants.UTF_8 ), port ));
	    	
		}catch( Throwable e ){
			
			return( null );
		}
	}
	
	protected void
	destroy()
	{
		DHTUDPUtils.unregisterAlternativeNetwork( tor_net );
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
			InetSocketAddress	address,
			boolean				is_local )
		{
			synchronized( address_history ){
				
				address_history.addFirst(new Object[]{  address, is_local?-1L:new Long( SystemTime.getMonotonousTime())});
				
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
					
					result.add( new DHTTransportAlternativeContactImpl((InetSocketAddress)entry[0],(Long)entry[1]));
					
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
			private final InetSocketAddress	address;
			private final int	 			seen_secs;
			private final int	 			id;
			
			private
			DHTTransportAlternativeContactImpl(
				InetSocketAddress	_address,
				long				seen )
			{
				address		= _address;
				
				seen_secs	= seen<0?-1:(int)( seen/1000 );				
			
				int	_id;
				
				try{
				
					_id = Arrays.hashCode( address.getHostString().getBytes( Constants.UTF_8 ));
					
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
				if ( seen_secs < 0 ){
					
					return((int)(SystemTime.getMonotonousTime()/1000));
							
				}else{
					
					return( seen_secs );
				}
			}
			
			@Override
			public int
			getAge()
			{
				if ( seen_secs < 0 ){
					
					return( 0 );
					
				}else{
					
					return(((int)( SystemTime.getMonotonousTime()/1000)) - seen_secs );
				}
			}
			
			@Override
			public Map<String,Object>
			getProperties()
			{
				Map<String,Object>	properties = new HashMap<String, Object>();
				
				try{			
					properties.put( "h", address.getHostString().getBytes( Constants.UTF_8 ));
					properties.put( "p", address.getPort());
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
				
				return( properties );
			}
		}
	}
}
