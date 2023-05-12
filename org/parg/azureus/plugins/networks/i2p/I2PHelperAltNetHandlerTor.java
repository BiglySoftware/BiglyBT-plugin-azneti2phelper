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
import java.util.Locale;
import java.util.Map;

import com.biglybt.core.util.BEncoder;
import com.biglybt.core.util.Base32;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.RandomUtils;
import com.biglybt.core.util.SystemTime;

import com.biglybt.core.dht.transport.DHTTransportAlternativeContact;
import com.biglybt.core.dht.transport.DHTTransportAlternativeNetwork;
import com.biglybt.core.dht.transport.udp.impl.DHTUDPUtils;

public class 
I2PHelperAltNetHandlerTor 
{
	public static final int LOCAL_VERSION	= 2;
	
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
	
	private static byte[]
	hostnameToBytes(
		String	host_name )
	{
		byte[] bytes = Base32.decode( host_name.substring( 0, host_name.indexOf( "." )));
		
			// v3 onion host names are always 35 bytes (32 from 512 bit key, 1 version, 2 checksum)
		
		if ( bytes.length != 35 ){
			
			Debug.out( "Hmm, " + host_name + " -> " + bytes.length );
		}
		
		return( bytes );
	}
	
	private static String
	bytesToHostname(
		byte[]		bytes )
	{
		if ( bytes.length == 35 ){
			
			return( Base32.encode(bytes).toLowerCase( Locale.US ) + ".onion" );
			
		}else{
				// migration
			
			return( new String( bytes, Constants.UTF_8 ));
		}
	}
	
	public static InetSocketAddress
	decodeContact(
		DHTTransportAlternativeContact		contact )
	{
		try{
			Map<String,Object>	map = contact.getProperties();
			
	    	byte[]	host_bytes 	= (byte[])map.get( "h" );	    	
	    	int		port 		= ((Number)map.get( "p" )).intValue();

	    	String host = bytesToHostname( host_bytes );
	
	    	return( InetSocketAddress.createUnresolved( host, port ));
	    	
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
		
		public InetSocketAddress 
		getNotionalAddress(
			DHTTransportAlternativeContact contact )
		{
			return( decodeContact( contact ));
		}
		
		private void
		addContact(
			InetSocketAddress	address,
			boolean				is_local )
		{
			synchronized( address_history ){
				
				address_history.addFirst(new Object[]{  address, is_local?-1L:new Long( SystemTime.getMonotonousTime())});
				
				if ( address_history.size() > ADDRESS_HISTORY_MAX ){
					
					Object[] entry = address_history.removeLast();
					
						// keep local contact around
					
					if (((Long)entry[1]) == -1 ){
						
						address_history.removeLast();
						
						address_history.addFirst( entry );
					}
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
					
						// important that we create a new contact here as the age of local contacts
						// is maintained as roughly "now"
					
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
			private final int				version;
			private final InetSocketAddress	address;
			private final int	 			seen_secs;
			private final int	 			id;
			
			private
			DHTTransportAlternativeContactImpl(
				InetSocketAddress	_address,
				long				seen )
			{
				address		= _address;
				
				if ( seen < 0 ){
					
						// local, make ourselves look a bit old so we don't always end up as the 
						// freshest contact
					
					seen_secs = (int)( SystemTime.getMonotonousTime()/1000) - 120 - RandomUtils.nextInt( 240 );
					
					version	= LOCAL_VERSION;
					
				}else{
				
					seen_secs	= (int)( seen/1000 );
					
					version = 0;	// means "unknown" for addresses added with no version context
				}
				
				int	_id;
				
				try{
				
					_id = Arrays.hashCode( BEncoder.encode( getProperties()));
					
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
				return( version );
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
					String host = address.getHostString();
										
					properties.put( "h", hostnameToBytes( host ));
					properties.put( "p", address.getPort());
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
				
				return( properties );
			}
		}
	}
}
