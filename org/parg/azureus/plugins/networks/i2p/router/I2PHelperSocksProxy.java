/*
 * Created on Apr 24, 2014
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



package org.parg.azureus.plugins.networks.i2p.router;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.client.streaming.impl.MessageInputStream;
import net.i2p.client.streaming.impl.MessageInputStream.ActivityListener;
import net.i2p.data.Base32;
import net.i2p.data.Destination;

import com.biglybt.core.tracker.protocol.PRHelpers;
import com.biglybt.core.util.AENetworkClassifier;
import com.biglybt.core.util.AERunnable;
import com.biglybt.core.util.AEThread2;
import com.biglybt.core.util.BEncoder;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.ThreadPool;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.torrent.Torrent;
import com.biglybt.pif.torrent.TorrentAnnounceURLListSet;
import org.parg.azureus.plugins.networks.i2p.I2PHelperAdapter;

import com.biglybt.core.proxy.AEProxyConnection;
import com.biglybt.core.proxy.AEProxyException;
import com.biglybt.core.proxy.AEProxyFactory;
import com.biglybt.core.proxy.AEProxyState;
import com.biglybt.core.proxy.socks.AESocksProxy;
import com.biglybt.core.proxy.socks.AESocksProxyAddress;
import com.biglybt.core.proxy.socks.AESocksProxyConnection;
import com.biglybt.core.proxy.socks.AESocksProxyFactory;
import com.biglybt.core.proxy.socks.AESocksProxyPlugableConnection;
import com.biglybt.core.proxy.socks.AESocksProxyPlugableConnectionFactory;

public class 
I2PHelperSocksProxy 
	implements AESocksProxyPlugableConnectionFactory
{
	private static final boolean TRACE = false;
	
	
	private InetAddress local_address;
	
	private Set<SOCKSProxyConnection>		connections = new HashSet<SOCKSProxyConnection>();
	
	private ThreadPool<AERunnable>	connect_pool = new ThreadPool<>( "I2PHelperSocksProxyConnect", 32 );

	{
		try{
			local_address = InetAddress.getByName( "127.0.0.1" );
			
		}catch( Throwable e ){
			
			Debug.printStackTrace(e);
			
			local_address = null;
		}
	}
	
	private I2PHelperRouter		router;
	private boolean				allow_public_fallback;
	private I2PHelperAdapter	adapter;
	
	private AESocksProxy 		proxy;
	
	private Map<String,Object[]>	intermediate_host_map		= new HashMap<String, Object[]>();

	protected Map<String,Object[]>	intermediate_host_old_map =
		new LinkedHashMap<String,Object[]>(256,0.75f,true)
		{
			@Override
			protected boolean
			removeEldestEntry(
		   		Map.Entry<String,Object[]> eldest)
			{
				return size() > 256;
			}
		};
	
	private int						next_intermediate_host	= 1;
	
	private boolean				destroyed;
	
	public
	I2PHelperSocksProxy(
		I2PHelperRouter			_router,
		int						_port,
		boolean					_allow_public_fallback,
		I2PHelperAdapter		_adapter )
	
		throws AEProxyException
	{
		router					= _router;
		allow_public_fallback	= _allow_public_fallback;
		adapter 				= _adapter;
								
		proxy = AESocksProxyFactory.create( _port, 120*1000, 120*1000, this );
		
		adapter.log( "Intermediate SOCKS proxy started on port " + proxy.getPort());
		
		/*
		SimpleTimer.addPeriodicEvent(
			"socks timer",
			5000,
			(e)->{
				tick();
			});
		*/
	}
	
	public int
	getPort()
	{
		return( proxy.getPort());
	}
	
	public String
	getIntermediateHost(
		String					host,
		Map<String,Object>		opts,
		Map<String,Object>		output )
		
		throws AEProxyException
	{
		long now =  SystemTime.getMonotonousTime() ;
		
		synchronized( this ){
			
			if ( destroyed ){
				
				throw( new AEProxyException( "Proxy destroyed" ));
			}
	
			while( true ){
				
				int	address = 0x0a000000 + ( next_intermediate_host++ );
					
				if ( next_intermediate_host > 0x00ffffff ){
					
					next_intermediate_host = 1;
				}
				
				String intermediate_host = PRHelpers.intToAddress( address );
				
				if ( !intermediate_host_map.containsKey( intermediate_host )){
					
					Object[] entry = new Object[]{ host, opts, output, now};
					
					intermediate_host_map.put( intermediate_host, entry );
					
					if ( intermediate_host_old_map.size() > 16 ){
						
						try{
							Iterator<Object[]> it = intermediate_host_old_map.values().iterator();
							
							while( it.hasNext()){
								
								if ( now - (Long)it.next()[3] > 2*60*1000 ){
									
									it.remove();
								}
							}
						}catch( Throwable e ){
							
							Debug.out( e );
						}
					}
					
					intermediate_host_old_map.put( intermediate_host, entry );
					
					return( intermediate_host );
				}
			}
		}
	}
	
	public void
	removeIntermediateHost(
		String		intermediate )
	{
		synchronized( this ){
			
			intermediate_host_map.remove( intermediate );
			
			intermediate_host_old_map.remove( intermediate );
		}
	}
	
	@Override
	public AESocksProxyPlugableConnection
	create(
		AESocksProxyConnection	connection )
	
		throws AEProxyException
	{
		synchronized( this ){
			
			if ( destroyed ){
				
				throw( new AEProxyException( "Proxy destroyed" ));
			}
			
			if ( connections.size() > 512 ){
				
				try{
					connection.close();
					
				}catch( Throwable e ){
				}
				
				throw( new AEProxyException( "Too many connections" ));
			}
		
			SOCKSProxyConnection con = new SOCKSProxyConnection( connection );
			
			connections.add( con );
			
			//System.out.println( "total connections=" + connections.size() + ", ih=" + intermediate_host_map.size());
			
			return( con );
		}
	}
	
	/*
	private void
	tick()
	{
		synchronized( this ){
	
			for ( SOCKSProxyConnection con: connections ){
				
				System.out.println( con.getName());
				
				con.tick();
			}
		}
	}
	*/
	
	private I2PSMHolder
	getSocketManager(
		Map<String,Object>		options )
	
		throws Exception
	{
		long	start = SystemTime.getMonotonousTime();
		
		while( SystemTime.getMonotonousTime() - start < 60*1000 ){
			
			if ( destroyed ){
				
				throw( new Exception( "SOCKS proxy destroyed" ));
			}
			
			I2PSMHolder sm = router.getSocketManagerForSocks( options );
			
			if ( sm != null ){
				
				return( sm );
			}
			
			try{
				Thread.sleep(1000);
				
			}catch( Throwable e ){
				
			}
		}
		
		throw( new Exception( "Timeout waiting for socket manager" ));
	}
	
	private I2PSocket
	connectToAddress(
		String				address,
		int					port,
		Map<String,Object>	options )
		
		throws Exception
	{
		boolean	logit = true;

		try{
			I2PSMHolder sm_holder = getSocketManager( options );

			Destination remote_dest = sm_holder.lookupAddress( address, adapter );
			
			if ( remote_dest.getHash().equals( sm_holder.getMyDestination().getHash())){
				
				logit = false;
				
				throw( new Exception( "Attempting to connect to ourselves" ));
			}
			
			Properties overrides = new Properties();
			
			overrides.setProperty( "i2p.streaming.connectDelay", "250" );
			
            I2PSocketOptions socket_opts = sm_holder.buildOptions( overrides );
            
            socket_opts.setPort( port );
            
            socket_opts.setConnectTimeout( 120*1000 );
            socket_opts.setReadTimeout( 120*1000 );
            socket_opts.setWriteTimeout( 120*1000 );
            
			I2PSocket socket = sm_holder.connect( remote_dest, socket_opts );
						
			adapter.outgoingConnection( socket );
			
			return( socket );
			
		}catch( UnknownHostException e ){
			
			throw( e );
			
		}catch( Throwable e ){
			
			if ( e instanceof NoRouteToHostException ){
				
				logit = false;
				
			}else if ( logit ){
				
				String msg = Debug.getNestedExceptionMessage(e).toLowerCase();
							
				if ( msg.contains( "timeout" ) || msg.contains( "timed out" ) || msg.contains( "reset" ) || msg.contains( "resolve" )){
					
					logit = false;
				}
			
				if ( logit ){
				
					e.printStackTrace();
				}
			}
			
			throw( new IOException( Debug.getNestedExceptionMessage(e)));
		}
	}
	
	private void
	removeConnection(
		SOCKSProxyConnection	connection )
	{
		synchronized( this ){
			
			connections.remove( connection );
			
			//System.out.println( "total connections=" + connections.size() + ", ih=" + intermediate_host_map.size());
		}
	}
	
	private void
	trace(
		String		str )
	{
		if ( TRACE ){
		
			adapter.log( str );
		}
	}
	
	public void
	destroy()
	{
		List<SOCKSProxyConnection>	to_close = new ArrayList<SOCKSProxyConnection>();
		
		synchronized( this ){
			
			if ( destroyed ){
				
				return;
			}
			
			synchronized( this ){ 
				
				destroyed = true;
			}
			
			to_close.addAll( connections );
			
			try{
				proxy.destroy();
			
			}catch( Throwable e ){
			}
		}
		
		for ( SOCKSProxyConnection c: to_close ){
			
			try{
				c.close();
				
			}catch( Throwable e ){
			}
		}
	}
	
	protected static ThreadPool<AERunnable>			async_read_pool 	= new ThreadPool<>( "I2PSocket relay read", 32, true );
	
		// write pool is blocking as there is no non-blocking support for writes to I2P
	
	protected static ThreadPool<AERunnable>			async_write_pool 	= new ThreadPool<>( "I2PSocket relay write", 256, true );
	
	private class
	SOCKSProxyConnection
		implements AESocksProxyPlugableConnection
	{
		
			// try to buffer at least a whole block
		
		public static final int RELAY_BUFFER_SIZE	= 64*1024 + 256;
		
		private I2PSocket					socket;
		private boolean						socket_closed;
		
		private AESocksProxyConnection		proxy_connection;
		private Map<String,Object>			options;
		private Map<String,Object>			output;
		
		private String						original_unresolved;
		private int							original_port;
		
		private proxyStateRelayData			relay_state;
		
		protected
		SOCKSProxyConnection(
			AESocksProxyConnection			_proxy_connection )
		{
			proxy_connection	= _proxy_connection;
			
			proxy_connection.disableDNSLookups();
		}
		
		@Override
		public String
		getName()
		{
			return( "I2PPluginConnection" );
		}
		
		@Override
		public InetAddress
		getLocalAddress()
		{
			return( local_address );
		}
		
		@Override
		public int
		getLocalPort()
		{
			return( -1 );
		}

		@Override
		public void
		connect(
			final AESocksProxyAddress		_address )
			
			throws IOException
		{
			InetAddress resolved 	= _address.getAddress();
			String		unresolved	= _address.getUnresolvedAddress();
							
			if ( resolved != null ){
				
				String ha = resolved.getHostAddress();
				
				synchronized( this ){
					
					Object[]	intermediate = intermediate_host_map.remove( ha );
					
					if ( intermediate == null ){
						
							// allow limited re-use of an address
							// of use, for example, when an HTTP URL connection needs to
							// be authenticated
						
						intermediate = intermediate_host_old_map.get( ha );
					}
					
					if ( intermediate != null ){
						
						resolved	= null;
						unresolved	= (String)intermediate[0];
						options		= (Map<String,Object>)intermediate[1];
						output		= (Map<String,Object>)intermediate[2];
					}
				}
			}
			
			original_unresolved	= unresolved;
			original_port		= _address.getPort();
			
			if ( TRACE )trace( "connect request to " + unresolved + "/" + resolved + "/" + _address.getPort() + "/" + BEncoder.encodeToJSON( options ));
					
			boolean		handling_connection = false;
			
			try{
				if ( resolved != null ){
						
					if ( !allow_public_fallback ){
													
						String	msg = "Connection refused, not delegating public address " + resolved + ":" + original_port;
						
							// filter out re-use of expired intermediate host address from logging
						
						if ( Constants.isCVSVersion() && !resolved.getHostAddress().startsWith( "10." )){
							
							System.err.println( "azneti2phelper: " + msg );
						}
						
						throw( new IOException( msg ));
					}
					
					trace( "    delegating resolved" );
						
					AESocksProxyPlugableConnection	delegate = proxy_connection.getProxy().getDefaultPlugableConnection( proxy_connection );
						
					proxy_connection.setDelegate( delegate );
						
					delegate.connect( _address );

				}else{ 
					
					final String	externalised_address = AEProxyFactory.getAddressMapper().externalise( unresolved );
				
					if ( AENetworkClassifier.categoriseAddress( externalised_address ) != AENetworkClassifier.AT_I2P ){ 
								
						if ( !allow_public_fallback ){
							
							String msg = "Connection refused, not delegating public address: " + externalised_address + ":" + original_port;
							
							if ( Constants.isCVSVersion()){
								
								System.err.println( "azneti2phelper: " + msg );
							}
							
							throw( new IOException(  msg ));
						}
						
						trace( "    delegating unresolved" );
	
						AESocksProxyPlugableConnection	delegate = proxy_connection.getProxy().getDefaultPlugableConnection( proxy_connection );
						
						proxy_connection.enableDNSLookups();
						
						proxy_connection.setDelegate( delegate );
						
						delegate.connect( _address );
	
					}else{
							
						connect_pool.run(
							new AERunnable()
							{
								@Override
								public void
								runSupport()
								{									
									trace( "    delegating to I2P" );
									
									try{
										
											// remove the .i2p
										
										String new_externalised_address = externalised_address.substring( 0, externalised_address.length() - 4 );
										
								        socket = connectToAddress( new_externalised_address, _address.getPort(), options );
								       	
								        proxy_connection.connected();
								        
								        if ( output != null ){
								        	
								        	output.put( "connected", true );
								        }
									}catch( Throwable e ){

										setError( e );
										
										try{
											proxy_connection.close();
											
										}catch( Throwable f ){
											
											f.printStackTrace();
										}
										
											//e.printStackTrace();
										
										trace( "I2PSocket creation fails: " + Debug.getNestedExceptionMessage(e) );
									}
								}
							});
						
						handling_connection = true;
					}
				}
			}finally{
				
				if ( !handling_connection ){
					
						// we've handed over control for this connection and won't hear about it again
					
					removeConnection( this );
				}
			}
		}
		
		/*
		private void
		tick()
		{
			synchronized( this ){
				
				if ( relay_state != null ){
					
					relay_state.tick();
				}
			}
		}
		*/
		
		private void
		setError(
			Throwable	e )
		{
			
			if ( output != null ){
				
				output.put( "error", e );
			}
		}
		
		@Override
		public void
		relayData()
		
			throws IOException
		{
			synchronized( this ){
			
				if ( socket_closed ){
				
					throw( new IOException( "I2PPluginConnection::relayData: socket already closed"));
				}
			
				relay_state = new proxyStateRelayData( proxy_connection.getConnection());
			}
		}
		
		@Override
		public void
		close()
		
			throws IOException
		{
			synchronized( this ){
			
				if ( socket != null && !socket_closed ){
					
					socket_closed	= true;
				
					if ( relay_state != null ){
						
						relay_state.close();
					}
					
					final I2PSocket	f_socket	= socket;
					
					socket	= null;
					
					AEThread2 t = 
						new AEThread2( "I2P SocketCloser" )
						{
							@Override
							public void
							run()
							{
								try{
									f_socket.close();
									
								}catch( Throwable e ){
									
								}
							}
						};
					
					t.start();
				}
			}
			
			removeConnection( this );
		}
		
		protected class
		proxyStateRelayData
			implements AEProxyState
		{
			private static final boolean LOG_CONTENT = false;
			
			AEProxyConnection		connection;
			ByteBuffer				source_buffer;
			
			volatile ByteBuffer		target_buffer;
			
			SocketChannel			source_channel;
			
			MessageInputStream		input_stream;
			OutputStream			output_stream;
			
			long					outward_bytes	= 0;
			long					inward_bytes	= 0;
				
			byte[]		i2p_input_buffer = new byte[RELAY_BUFFER_SIZE];
			boolean		i2p_read_active;
			boolean		i2p_read_deferred;
			
			boolean 	i2p_read_dead;

			Object lock = new Object();
			
			protected
			proxyStateRelayData(
				AEProxyConnection	_connection )
			
				throws IOException
			{		
				// System.out.println( "Relay start: " + socket.getPeerDestination());

				connection	= _connection;
				
				source_channel	= connection.getSourceChannel();
				
				source_buffer	= ByteBuffer.allocate( RELAY_BUFFER_SIZE );
				
				input_stream 	= (MessageInputStream)socket.getInputStream();
				output_stream 	= socket.getOutputStream();

				input_stream.setReadTimeout( 0 );	// non-blocking
				
				connection.setReadState( this );
				
				connection.setWriteState( this );
				
				connection.requestReadSelect( source_channel );
							
				connection.setConnected();
				
				input_stream.setActivityListener(
					new ActivityListener(){
						
						@Override
						public void 
						activityOccurred()
						{
							readFromI2P();
						}
					});
				
				readFromI2P();
			}
			
			/*
			private void
			tick()
			{
				try{
					System.out.println( "    " + getStateName() + " - ready=" + input_stream.available());
					
				}catch( Throwable e ){
					
				}
			}
			*/
			
			private void
			readFromI2P()
			{
				synchronized( lock ){
					
					if ( i2p_read_dead ){
						
						return;
					}
					
					if ( i2p_read_active || target_buffer != null ){
						
						i2p_read_deferred = true;
						
						return;
					}
					
					i2p_read_active = true;
				}
				
				async_read_pool.run(
					new AERunnable()
					{
						@Override
						public void
						runSupport()
						{	
							boolean	went_async = false;

							try{											
								while( !connection.isClosed()){
								
									try{
										trace( "I2PCon: " + getStateName() + " : read Starts <- I2P " );
	
										long	start = System.currentTimeMillis();
										
										int	len = input_stream.read( i2p_input_buffer );
										
										if ( len == 0 ){
											
											synchronized( lock ){
												
												if ( !i2p_read_deferred ){
																								
													went_async = true;
													
													return;
												}
												
												i2p_read_deferred = false;
											}
											
											continue;
										}
										
										if ( len < 0 ){
											
											throw( new IOException( "Connection closed" ));
										}
																	
										trace( "I2PCon: " + getStateName() + " : read Done <- I2P - " + len + ", elapsed = " + ( System.currentTimeMillis() - start ));
										
										if ( target_buffer != null ){
											
											Debug.out("I2PluginConnection: target buffer should be null" );
										}
										
										// System.out.println( new String( buffer, 0, len ));
										
										target_buffer = ByteBuffer.wrap( i2p_input_buffer, 0, len );
										
										read();
											
										if ( target_buffer != null ){
											
											went_async = true;
											
											return;
										}
									}catch( Throwable e ){
										
										setError( e );
										
										boolean ignore = false;
										
										if ( 	e instanceof ClosedChannelException ||
												e instanceof SocketTimeoutException ){
											
											ignore = true;
											
										}else if ( e instanceof IOException ){
											
											String message = Debug.getNestedExceptionMessage( e );
											
											if ( message != null ){
												
												message = message.toLowerCase( Locale.US );
											
												if (	message.contains( "closed" ) ||
														message.contains( "aborted" ) ||
														message.contains( "disconnected" ) ||
														message.contains( "reset" )){
										
													ignore = true;
												}
											}
										}
										
										if ( !ignore ){
											
											Debug.out( e );
										}
										
										break;
									}
								}
								
								if ( !proxy_connection.isClosed()){
									
									try{
										proxy_connection.close();
										
									}catch( IOException e ){
										
										Debug.printStackTrace(e);
									}
								}
							}finally{
							
								synchronized( lock ){
									
									i2p_read_active = false;
									
									if (!went_async ){
										
										i2p_read_dead = true;
									}
									
									if ( i2p_read_deferred ){
										
										i2p_read_deferred = false;
										
										readFromI2P();
									}
								}
							}
						}
					});
			}
			
			protected void
			close()
			{	
				// System.out.println( "Relay end: " + socket.getPeerDestination());
				
				trace( "I2PCon: " + getStateName() + " close" );
			}
			
			protected void
			read()
			
				throws IOException
			{
					// data from I2P
				
				connection.setTimeStamp();
			
				if ( LOG_CONTENT ){
					System.out.println( new String( target_buffer.array(), target_buffer.arrayOffset(), target_buffer.remaining()));
				}
				
				int written = source_channel.write( target_buffer );
					
				trace( "I2PCon: " + getStateName() + " : write -> AZ - " + written );
				
				inward_bytes += written;
				
				if ( target_buffer.hasRemaining()){
				
					connection.requestWriteSelect( source_channel );
					
				}else{
				
					target_buffer	= null;
				}
			}
			
			@Override
			public boolean
			read(
				SocketChannel 		sc )
			
				throws IOException
			{
				if ( source_buffer.position() != 0 ){
					
					Debug.out( "I2PluginConnection: source buffer position invalid" );
				}
				
					// data read from source
				
				connection.setTimeStamp();
																
				final int	len = sc.read( source_buffer );
		
				if ( len == 0 ){
					
					return( false );
				}
				
				if ( len == -1 ){
					
					throw( new EOFException( "read channel shutdown" ));
					
				}else{
					
					if ( source_buffer.position() > 0 ){
						
						connection.cancelReadSelect( source_channel );
						
						trace( "I2PCon: " + getStateName() + " : read <- AZ - " + len );
						
							// offload the write to separate thread as can't afford to block the
							// proxy
					
						async_write_pool.run(
							new AERunnable()
							{
								@Override
								public void
								runSupport()
								{
									try{					
										source_buffer.flip();
										
										long	start = System.currentTimeMillis();
										
										trace( "I2PCon: " + getStateName() + " : write Starts -> I2P - " + len );
										
											/*	The I2PTunnel code ends up spewing headers like this:
											 	GET / HTTP/1.1
												Host: ladedahdedededed.b32.i2p
												Cookie: PHPSESSID=derptyderp
												Cache-Control: max-age=0
												Accept-Encoding: 
												X-Accept-Encoding: x-i2p-gzip;q=1.0, identity;q=0.5, deflate;q=0, gzip;q=0, *;q=0
												User-Agent: MYOB/6.66 (AN/ON)
												Connection: close
												
												Some services insist on the referrer being removed otherwise the throw 'permission denied' errors
												Also probably makes sense to switch the Host: record to always be the b32 address.
												
												Note the switch in accept-encoding. This doesn't appear to be required from some testing
												I've done but again something to look out for
												
																							
											 	I2PTunnelHTTPServer adds the following headers, might be worth a look one day
										     	private static final String HASH_HEADER = "X-I2P-DestHash";
										    	private static final String DEST64_HEADER = "X-I2P-DestB64";
										    	private static final String DEST32_HEADER = "X-I2P-DestB32";
									        	addEntry(headers, HASH_HEADER, peerHash.toBase64());
									        	addEntry(headers, DEST32_HEADER, Base32.encode(peerHash.getData()) + ".b32.i2p");
									        	addEntry(headers, DEST64_HEADER, socket.getPeerDestination().toBase64());

												str = str.replace( "Connection: keep-alive", "Connection: close" );

											 */
										
											// gonna be lazy here and assume that if this is an HTTP request then the
											// headers are present in the initial buffer read
										
										byte[] 	array 			= source_buffer.array();
										int		array_offset	= 0;
										
										if ( outward_bytes == 0 ){
																				
											for ( int i=0;i<len-3;i++){
												
												if ( 	array[i] 	== '\r' &&
														array[i+1]	== '\n' &&
														array[i+2]	== '\r' &&
														array[i+3]	== '\n' ){
													
													String str = new String( array, 0, i+2, "ISO8859-1" );

													if ( LOG_CONTENT ){
														System.out.println( str );
													}
													
													String[] lines = str.split( "\r\n" );
													
													boolean is_http = false;
													
													List<String>	headers = new ArrayList<String>();
													
													for ( int j=0;j<lines.length;j++){
													
														String line = lines[j].trim();
														
														if ( j == 0 ){
															
															int pos1 = line.indexOf(' ');
															int pos2 = line.lastIndexOf( ' ' );
															
															if ( pos1 != -1 && pos2 != -1 ){
																
																String method = line.substring( 0,  pos1 ).toUpperCase(Locale.US);
																
																if ( method.equals( "GET" ) || method.equals( "HEAD" ) || method.equals( "POST")){
																	
																	String protocol = line.substring( pos2 + 1 ).toUpperCase( Locale.US );
																	
																	if ( protocol.startsWith( "HTTP" )){
																		
																		is_http = true;
																	}
																}
															}
														
															if ( !is_http ){
																
																break;
																
															}else{
																
																String url_part = line.substring( pos1+1, pos2 ).trim();
																
																int	pos = url_part.indexOf( '?' );
																
																if ( pos != -1 ){
																																		
																	String[]	args = url_part.substring( pos+1 ).split( "&" );
																	
																	Map<String,String> arg_map = new HashMap<String,String>();
																	
																	for ( String arg: args ){
																		
																		String[] bits = arg.split( "=", 2 );
																		
																		if ( bits.length == 2 ){
																			String 	lhs = bits[0];
																			String	rhs = bits[1];
																			
																			arg_map.put( lhs, rhs );
																		}
																	}
																	
																	if ( 	arg_map.containsKey( "info_hash" ) &&
																			arg_map.containsKey( "peer_id" ) &&
																			arg_map.containsKey( "uploaded" )){
																		
																		StringBuffer sb = new StringBuffer( 1024 );
																		
																		sb.append( line.substring( 0, pos + 1 + pos1 + 1 ));
																		
																		sb.append( "info_hash=" + arg_map.get( "info_hash" ));
																		sb.append( "&peer_id=" + arg_map.get( "peer_id" ));
																		sb.append( "&port=6881" );
																		sb.append( "&ip=" + getSocketManager( options ).getMyDestination().toBase64() + ".i2p" );
																		sb.append( "&uploaded=" + arg_map.get( "uploaded" ));
																		sb.append( "&downloaded=" + arg_map.get( "downloaded" ));
																		sb.append( "&left=" + arg_map.get( "left" ));
																		sb.append( "&compact=1" );
																		
																		String event = arg_map.get( "event" );
																		
																		if ( event != null ){
																			sb.append( "&event=" + event );
																		}
																		
																		String num_want = arg_map.get( "numwant" );
																		
																		if ( num_want != null ){
																			sb.append( "&numwant=" + num_want );
																		}else{
																			//if ( event != null )
																		}
																		
																			// lastly patch in any existing url params
																		
																		PluginInterface pi = adapter.getPluginInterface();
																				
																		if ( pi != null ){
																			
																			try{
																				byte[] hash = URLDecoder.decode(arg_map.get( "info_hash" ), "ISO-8859-1").getBytes( "ISO-8859-1" );
																				
																				Download dl = pi.getDownloadManager().getDownload( hash );
																				
																				if ( dl != null ){
																				
																					Torrent t = dl.getTorrent();
																					
																					if ( t != null ){
																					
																						List<URL>	urls = new ArrayList<URL>();
																					
																						urls.add( t.getAnnounceURL());
																					
																						for ( TorrentAnnounceURLListSet set: t.getAnnounceURLList().getSets()){
																							
																							urls.addAll( Arrays.asList(set.getURLs()));
																						}
																						
																						for ( URL u: urls ){
																							
																							if ( u == null ){
																								
																								continue;
																							}
																							
																							if ( u.getHost().equals( original_unresolved )){
																								
																								int	u_port = u.getPort();
																								
																								if ( u_port == -1 ){
																									
																									u_port = 80;
																								}
																								
																								if ( u_port == original_port || u_port == 80 && original_port == -1 ){
																									
																									String query = u.getQuery();
																									
																									if ( query != null && query.length() > 0 ){
																										
																										sb.append( "&" + query );
																										
																										break;
																									}
																								}
																							}
																						}
																					}
																				}																				
																			}catch( Throwable e ){
																				
																			}
																		}
																		
																		sb.append( line.substring( pos2 ));
																		
																		line = sb.toString();
																		
																		// System.out.println( line );
																	}
																}
													
																headers.add( line );
															}
														}else{
														
															String[] bits = line.split( ":", 2 );
															
															if ( bits.length != 2 ){
																
																headers.add( line );
																
															}else{
															
																String	kw = bits[0].toUpperCase( Locale.US );
																
																if ( kw.equals( "REFERER" )){
																	
																	// skip it
																	
																}else if ( kw.equals( "HOST" )){
																	
																	I2PSocket s = socket;
																	
																	Destination peer_dest = s==null?null:s.getPeerDestination();
																	
																	if ( peer_dest == null ){
																		
																		throw( new IOException( "Socked closed" ));
																	}
																	
																	String target_host = bits[1];
																	
																	int port = 0;
																	
																	int	pos = target_host.indexOf( ':' );
																	
																	if ( pos != -1 ){
																		
																		port = Integer.parseInt( target_host.substring( pos+1 ));
																	}
																	
																	String host_header =  "Host: " + Base32.encode( peer_dest.calculateHash().getData()) + ".b32.i2p";
						
																	if ( port > 0 ){
																		
																		host_header += ":" + port;
																	}
																	
																	headers.add( host_header );
																
																}else if ( kw.equals( "USER-AGENT" )){
	
																	headers.add( "User-Agent: " + Constants.APP_NAME );

																}else{
																	
																	headers.add( line );
																}
															}
														}
													}
													
													if ( is_http ){
														
														StringBuilder sb = new StringBuilder( len+128 );
														
														for ( String header: headers ){
															
															sb.append( header );
															sb.append( "\r\n" );
														}
														
														sb.append( "\r\n" );
														
														String oh = sb.toString();
														
														//System.out.println( oh );
														
														byte[] output_headers = oh.getBytes( "ISO8859-1" );
														
														output_stream.write( output_headers );
													
														array_offset = i+4;
													}
													
													break;
												}																				
											}
										}
										
										int	rem = len - array_offset;
										
										if ( rem > 0 ){

											output_stream.write( array, array_offset, rem );
										}
										
										source_buffer.position( 0 );
										
										source_buffer.limit( source_buffer.capacity());
										
										output_stream.flush();
										
										trace( "I2PCon: " + getStateName() + " : write done -> I2P - " + len + ", elapsed = " + ( System.currentTimeMillis() - start ));
										
										outward_bytes += len;
										
										connection.requestReadSelect( source_channel );								

									}catch( Throwable e ){
										
										setError( e );
										
										connection.failed( e );
									}
								}
							});			
					}
				}
				
				return( true );
			}
			
			@Override
			public boolean
			write(
				SocketChannel 		sc )
			
				throws IOException
			{
				
				try{
					if ( LOG_CONTENT ){
						System.out.println( new String( target_buffer.array(), target_buffer.arrayOffset(), target_buffer.remaining()));
					}
					
					int written = source_channel.write( target_buffer );
						
					inward_bytes += written;
						
					trace( "I2PCon: " + getStateName() + " write -> AZ: " + written );
					
					if ( target_buffer.hasRemaining()){
										
						connection.requestWriteSelect( source_channel );
						
					}else{
						
						synchronized( lock ){
							
							target_buffer = null;
							
							readFromI2P();
						}
					}
					
					return( written > 0 );
					
				}catch( Throwable e ){
										
					if (e instanceof IOException ){
						
						throw((IOException)e);
					}
					
					throw( new IOException( "write fails: " + Debug.getNestedExceptionMessage(e)));
				}
			}
			
			@Override
			public boolean
			connect(
				SocketChannel	sc )
			
				throws IOException
			{
				throw( new IOException( "unexpected connect" ));
			}
			
			@Override
			public String
			getStateName()
			{
				String	state = this.getClass().getName();
				
				int	pos = state.indexOf( "$");
				
				state = state.substring(pos+1);
				
				return( state  +" [out=" + outward_bytes +",in=" + inward_bytes +"] " + (source_buffer==null?"":source_buffer.toString()) + " / " + target_buffer );
			}
		}
	}
}
