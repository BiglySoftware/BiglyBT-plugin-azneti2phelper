/*
 * Created on Jul 25, 2014
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details ( see the LICENSE file ).
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */



package org.parg.azureus.plugins.networks.i2p.vuzedht;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;

import com.biglybt.core.util.Debug;

import com.biglybt.core.dht.*;
import com.biglybt.core.dht.transport.DHTTransportContact;
import com.biglybt.core.dht.transport.DHTTransportProgressListener;
import com.biglybt.core.dht.transport.DHTTransportTransferHandler;
import com.biglybt.core.dht.transport.DHTTransportValue;
import com.biglybt.plugin.dht.*;
import com.biglybt.plugin.dht.DHTPluginInterface.DHTInterface;

public abstract class 
I2PHelperAZDHT 
	implements DHTInterface
{
	public static final short		FLAG_NONE			= DHT.FLAG_NONE;			
	public static final short		FLAG_NON_ANON		= DHT.FLAG_SINGLE_VALUE;	// getters will get putter's address
	public static final short		FLAG_ANON			= DHT.FLAG_ANON;			// getters don't get putters address
	public static final short		FLAG_HIGH_PRIORITY	= DHT.FLAG_HIGH_PRIORITY;		

	public abstract boolean
	isInitialised();
	
	public abstract boolean
	waitForInitialisation(
		long	max_millis );
	
	public abstract DHT
	getDHT()
	
		throws Exception;
	
	public abstract DHT
	getBaseDHT()
	
		throws Exception;
	
	public void
	put(
		final byte[]						key,
		String								description,
		byte[]								value,
		short								flags,
		boolean								high_priority,
		final DHTPluginOperationListener	listener)
	{
		if ( high_priority ){
			
			flags |= FLAG_HIGH_PRIORITY;
		}
		
		try{
			getDHT().put(
				key, 
				description, 
				value, 
				flags, 
				high_priority,
				new ListenerWrapper( key, listener ));
			
		}catch( Throwable e ){
			
			Debug.out( e );
			
			listener.complete( key, false );
		}
	}
	
	public void
	get(
		final byte[]						key,
		String								description,
		short								flags,
		int									max_values,
		long								timeout,
		boolean								exhaustive,
		boolean								high_priority,
		final DHTPluginOperationListener	listener)
	{		
		if ( high_priority ){
			
			flags |= FLAG_HIGH_PRIORITY;
		}
		
		try{
			getDHT().get(
				key, 
				description, 
				flags, 
				max_values,
				timeout,
				exhaustive,
				high_priority,
				new ListenerWrapper( key, listener ));
			
		}catch( Throwable e ){
			
			Debug.out( e );
			
			listener.complete( key, false );
		}
	}
	
	public void
	remove(
		final byte[]						key,
		String								description,
		final DHTPluginOperationListener	listener)
	{
		try{
			getDHT().remove(
				key, 
				description, 
				new ListenerWrapper( key, listener ));
			
		}catch( Throwable e ){
			
			Debug.out( e );
			
			listener.complete( key, false );
		}
	}
	
	private Map<DHTPluginTransferHandler,DHTTransportTransferHandler>	handler_map = new HashMap<DHTPluginTransferHandler, DHTTransportTransferHandler>();
	
	public void
	registerHandler(
		byte[]							handler_key,
		final DHTPluginTransferHandler	handler,
		final Map<String,Object>		options )
	{		
		try{
			DHTTransportTransferHandler h =	
				new DHTTransportTransferHandler()
				{
					@Override
					public String
					getName()
					{
						return( handler.getName());
					}
					
					@Override
					public byte[]
					handleRead(
						DHTTransportContact	originator,
						byte[]				key )
					{
						return( handler.handleRead( new DHTContactImpl( originator ), key ));
					}
					
					@Override
					public byte[]
					handleWrite(
							DHTTransportContact	originator,
						byte[]				key,
						byte[]				value )
					{
						return( handler.handleWrite( new DHTContactImpl( originator ), key, value ));
					}
				};
				
			synchronized( handler_map ){
				
				if ( handler_map.containsKey( handler )){
					
					Debug.out( "Warning: handler already exists" );
				}else{
					
					handler_map.put( handler, h );
				}
			}
			
			getDHT().getTransport().registerTransferHandler( handler_key, h, options );

		}catch( Throwable e ){
			
			Debug.out( e );
		}
	}
	
	public void
	unregisterHandler(
		byte[]							handler_key,
		final DHTPluginTransferHandler	handler )
	{		
		DHTTransportTransferHandler h;
		
		synchronized( handler_map ){
		
			h = handler_map.remove( handler );
		}
		
		if ( h == null ){
			
			Debug.out( "Mapping not found for handler" );
			
		}else{
			
			try{
				getDHT().getTransport().unregisterTransferHandler( handler_key, h );

			}catch( Throwable e ){
				
				Debug.out( e );
			}
		}
	}
	
	public DHTPluginContact
	importContact(
		InetSocketAddress				address )
	{
		try{
			return( new DHTContactImpl(((DHTTransportAZ)getDHT().getTransport()).importContact(address)));
			
		}catch( Throwable e ){
			
			Debug.out( e );
			
			return( null );
		}
	}
	
	public DHTPluginContact
	importContact(
		Map<String,Object>				map )
	{
		try{
			return( new DHTContactImpl(((DHTTransportAZ)getDHT().getTransport()).importContact(map)));
			
		}catch( Throwable e ){
			
			Debug.out( e );
			
			return( null );
		}
	}
	
	private class
	ListenerWrapper
		implements DHTOperationListener
	{
		private final byte[]							key;
		private final DHTPluginOperationListener		listener;
		
		private boolean	started;
		
		private 
		ListenerWrapper(
			byte[]							_key,
			DHTPluginOperationListener		_listener )
		{
			key			= _key;
			listener 	= _listener;
		}
		
		@Override
		public void
		searching(
			DHTTransportContact	contact,
			int					level,
			int					active_searches )
		{
			if ( listener != null ){
				
				synchronized( this ){
					
					if ( started ){
						
						return;
					}
					
					started = true;
				}
				
				listener.starts( key );
			}
		}
		
		@Override
		public boolean
		diversified(
			String				desc )
		{
			return( listener.diversified());
		}
		
		@Override
		public void
		found(
			DHTTransportContact		contact,
			boolean					is_closest )
		{
			// nada
		}
		
		@Override
		public void
		read(
			DHTTransportContact		contact,
			DHTTransportValue		value )
		{
			listener.valueRead( new DHTContactImpl( value.getOriginator()), new DHTValueImpl( value ));
		}
		
		@Override
		public void
		wrote(
			DHTTransportContact		contact,
			DHTTransportValue		value )
		{
			listener.valueWritten( new DHTContactImpl( contact ), new DHTValueImpl( value ));
		}
		
		@Override
		public void
		complete(
			boolean					timeout )
		{
			listener.complete( key, timeout );
		}
	};
	
	
	protected class
	DHTContactImpl
		implements DHTContact
	{
		private DHTTransportContact		contact;
		
		protected
		DHTContactImpl(
			DHTTransportContact		_c )
		{
			contact = _c;
		}
		
		
		@Override
		public byte[]
		getID()
		{
			return( contact.getID());
		}
		
		@Override
		public int 
		getNetwork() 
		{
			return( contact.getTransport().getNetwork());
		}
		
		@Override
		public String
		getName()
		{
			return( contact.getName());
		}
		
		@Override
		public InetSocketAddress
		getAddress()
		{
			return( contact.getAddress());
		}
		
		@Override
		public byte
		getProtocolVersion()
		{
			return( contact.getProtocolVersion());
		}
		
		@Override
		public Map<String, Object>
		exportToMap()
		{
			return( contact.exportContactToMap());
		}
		
		@Override
		public boolean
		isAlive(
			long		timeout )
		{
			return( false );
		}
		
		@Override
		public void isAlive(long timeout, DHTPluginOperationListener listener) {
			Debug.out( "not imp" );
		}
		
		@Override
		public boolean isOrHasBeenLocal() {
			Debug.out( "not imp" );
			return false;
		}
		
		@Override
		public Map openTunnel() {
			Debug.out( "not imp" );
			return null;
		}
		
		@Override
		public byte[] 
		read(
			final DHTPluginProgressListener 	listener,
			byte[] 								handler_key, 
			byte[] 								key, 
			long 								timeout )
		{
			try{
				return( getDHT().getTransport().readTransfer(
					listener == null ? null :
							new DHTTransportProgressListener()
							{
								@Override
								public void
								reportSize(
									long	size )
								{
									listener.reportSize( size );
								}
								
								@Override
								public void
								reportActivity(
									String	str )
								{
									listener.reportActivity( str );
								}
								
								@Override
								public void
								reportCompleteness(
									int		percent )
								{
									listener.reportCompleteness( percent );
								}
							},
							contact, 
							handler_key, 
							key, 
							timeout ));
				
			}catch( Throwable e ){
				
				throw( new RuntimeException( e ));
			}
		}
		
		@Override
		public void
		write(
			final DHTPluginProgressListener 	listener,
			byte[] 								handler_key, 
			byte[] 								key, 
			byte[]								data,
			long 								timeout )
		{
			try{
				 getDHT().getTransport().writeTransfer(
						listener == null ? null :
						new DHTTransportProgressListener()
						{
							@Override
							public void
							reportSize(
								long	size )
							{
								listener.reportSize( size );
							}
							
							@Override
							public void
							reportActivity(
								String	str )
							{
								listener.reportActivity( str );
							}
							
							@Override
							public void
							reportCompleteness(
								int		percent )
							{
								listener.reportCompleteness( percent );
							}
						},
						contact, 
						handler_key, 
						key,
						data,
						timeout );
				
			}catch( Throwable e ){
				
				throw( new RuntimeException( e ));
			}
		}
		
		@Override
		public byte[] 
		call(
			final DHTPluginProgressListener 	listener,
			byte[] 								handler_key, 
			byte[] 								data, 
			long 								timeout )
		{
			try{
				return( getDHT().getTransport().writeReadTransfer(
						listener == null ? null :
						new DHTTransportProgressListener()
						{
							@Override
							public void
							reportSize(
								long	size )
							{
								listener.reportSize( size );
							}
							
							@Override
							public void
							reportActivity(
								String	str )
							{
								listener.reportActivity( str );
							}
							
							@Override
							public void
							reportCompleteness(
								int		percent )
							{
								listener.reportCompleteness( percent );
							}
						},
						contact, 
						handler_key, 
						data, 
						timeout ));
				
			}catch( Throwable e ){
				
				throw( new RuntimeException( e ));
			}
		}
		
		@Override
		public String 
		getString() 
		{
			return( contact.getString());
		}
	}
	
	private class
	DHTValueImpl
		implements DHTValue
	{
		private DHTTransportValue		value;
		
		private 
		DHTValueImpl(
			DHTTransportValue		_v )
		{
			value	= _v;
		}
		
		@Override
		public byte[]
		getValue()
		{
			return( value.getValue());
		}
		
		@Override
		public long getCreationTime() {
			return( value.getCreationTime());
		}
		
		@Override
		public int getFlags() {
			return( value.getFlags());
		}
		
		@Override
		public long getVersion() {
			return( value.getVersion());
		}
		@Override
		public boolean isLocal() {
			return( value.isLocal());
		}
		
		@Override
		public DHTContact getOriginator() {
			return( new DHTContactImpl( value.getOriginator()));
		}
	}
	
	public interface
	DHTContact
		extends DHTPluginContact
	{
	}
	
	public interface 
	DHTValue 
		extends DHTPluginValue
	{
		public DHTContact
		getOriginator();
	}
	
	public interface
	OperationListener
		extends DHTPluginOperationListener
	{
		public void
		valueRead(
			DHTContact			originator,
			DHTValue			value );
		
		public void
		valueWritten(
			DHTContact			target,
			DHTValue			value );
	}
	
	public static class
	OperationAdapter
		implements OperationListener
	{
		@Override
		public void
		starts(
			byte[]				key )
		{
		}
		
		@Override
		public boolean
		diversified()
		{
			return( true );
		}
		
		@Override
		public void
		valueRead(
			DHTPluginContact	originator,
			DHTPluginValue		value )
		{
			valueRead((DHTContact)originator, (DHTValue)value );
		}
		
		@Override
		public void
		valueRead(
			DHTContact			originator,
			DHTValue			value )
		{
		}
		
		@Override
		public void
		valueWritten(
			DHTPluginContact	target,
			DHTPluginValue		value )
		{
			valueWritten((DHTContact)target, (DHTValue)value );
		}
		
		@Override
		public void
		valueWritten(
			DHTContact			target,
			DHTValue			value )
		{
		}
		
		@Override
		public void
		complete(
			byte[]				key,
			boolean				timeout_occurred )
		{
		}
	}
}
