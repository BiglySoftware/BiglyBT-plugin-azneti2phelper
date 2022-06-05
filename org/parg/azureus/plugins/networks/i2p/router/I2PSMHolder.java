/*
 * Copyright (C) Bigly Software, Inc, All Rights Reserved.
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
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307  USA
 */

package org.parg.azureus.plugins.networks.i2p.router;

import java.io.File;
import java.net.UnknownHostException;
import java.util.*;

import org.parg.azureus.plugins.networks.i2p.I2PHelperAdapter;

import com.biglybt.core.util.AEThread2;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TimerEventPeriodic;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.I2PSessionMuxedListener;
import net.i2p.client.SendMessageOptions;
import net.i2p.client.datagram.I2PDatagramMaker;
import net.i2p.client.naming.NamingService;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.client.streaming.RouterRestartException;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.PrivateKeyFile;

public abstract class 
I2PSMHolder
{
	protected abstract String
	getName();
	
	protected abstract I2PSocketManager
	createSocketManager(
		boolean		recovering )
	
		throws Exception;
	
	protected abstract I2PSession
	getSession(
		I2PSocketManager		sm )
	
		throws Exception;
	
	protected abstract boolean
	isReady();
	
	protected abstract void
	logMessage(
		String		str );
	
	private final I2PHelperRouter			router;
	
	private volatile I2PSocketManager		socket_manager;
	private volatile I2PSession				session;
	
	private volatile I2PServerSocket		server_socket;
	
	private Properties				updated_options;
	private List<Object[]>			muxer_listeners = new ArrayList<>();
	
	private TimerEventPeriodic		timer_event;
	
	private boolean reconnecting;
	
	private volatile boolean	destroyed;
	
	public 
	I2PSMHolder(
		I2PHelperRouter		_router )
	
		throws Exception
	{
		router	= _router;
		
		socket_manager = createSocketManager( false );
			
		if ( socket_manager == null ){
			
			throw( new Exception( "SocketManager must not be null" ));
		}
	
		try{
			session = getSession( socket_manager );
			
			if ( session == null ){
				
				throw( new Exception( "Session must not be null" ));
			}
		}finally{
			
			if ( session == null && socket_manager != null ){
				
				try{
					socket_manager.destroySocketManager();
					
					socket_manager = null;
					
				}catch( Throwable e ){
					
				}
			}
		}
		
		timer_event = 
			SimpleTimer.addPeriodicEvent(
				"I2P Session Checker",
				30*1000,
				(e)->{
					isSessionClosedSupport();
				});
	}
	
	private boolean
	isSessionClosedSupport()
	{
		try{
			return( isSessionClosedSupport( false ));
			
		}catch( Throwable e ){
			
			return( true );
		}
	}
	
	private boolean
	isSessionClosedSupport(
		boolean	throw_if_closed )
	
		throws Exception
	{
		boolean	closed = session.isClosed();
		
		if ( closed ){
			
			synchronized( this ){
				
				if ( ! ( destroyed || router.isDestroyed())){
					
					if ( !reconnecting ){
						
						reconnecting = true;
						
						logMessage( "I2P session closed, reconnecting..." );
												
						AEThread2.createAndStartDaemon(
							"I2PSocketManager - reconnect",
							()->{								
								try{
									try{
										socket_manager.destroySocketManager();
										
									}catch( Throwable e ){
									}

									while( true ){
										
										if ( destroyed ){
											
											break;
										}
										
										I2PSocketManager rep_sm = null;
										
										try{
											rep_sm = createSocketManager( true );
											
											System.out.println( "Created socket manager for " + getName());
											
											I2PSession	rep_session = getSession( rep_sm );
											
											if ( rep_session != null ){
												
												System.out.println( "Got session for " + getName());
												
												synchronized( I2PSMHolder.this ){
													
													if ( updated_options != null ){
													
														rep_session.updateOptions( updated_options );
													}
													
													for ( Object[] entry: muxer_listeners ){
														
														rep_session.addMuxedSessionListener( (I2PSessionMuxedListener)entry[0], (Integer)entry[1], (Integer)entry[2] );
													}
													
													socket_manager		= rep_sm;
													session				= rep_session;
													server_socket		= null;
													
													rep_sm = null;
												}
												
												logMessage( "I2P session reconnected" );
												
												break;
											}
										}catch( Throwable e ){
											
											if ( rep_sm != null ){
												
												rep_sm.destroySocketManager();
											}
										}
										
										try{
											Thread.sleep( 30*1000 );
											
										}catch( Throwable e ){
											
											e.printStackTrace();
											
											break;
										}
									}
								}finally{
									
									synchronized( I2PSMHolder.this ){
										
										reconnecting = false;
									}
								}
							});
					}
				}
			}
		}
		
		if ( closed && throw_if_closed ){
		
			throw( new Exception( "Session is closed" ));
		}
		
		return( closed );
	}
	
	public boolean
	isSessionClosed()
	{
		return( isSessionClosedSupport());
	}
	
	public Destination
	getMyDestination()
	
		throws Exception
	{
		return( session.getMyDestination());
	}
	
	public void
	writePublicKey(
		File		file )
	
		throws Exception
	{
		new PrivateKeyFile( file, session ).write();
	}
	
	public Destination
	lookupDest(
		String		address,
		long		timeout )
	
		throws Exception
	{
		isSessionClosedSupport( true );
			
		long start = SystemTime.getMonotonousTime();
		
		boolean ok = false;
		
		try{
			Destination result =  session.lookupDest( address, timeout );
		
			ok = result != null;
			
			return( result );
			
		}finally{
			
			//System.out.println( "Session: " + address + " - " + ok + " - " + ( SystemTime.getMonotonousTime() - start ));
		}
	}
	
	public Destination
	lookupDest(
		Hash		address,
		long		timeout )
	
		throws Exception
	{
		isSessionClosedSupport( true );

		long start = SystemTime.getMonotonousTime();
		
		boolean ok = false;

		try{
		
			Destination result = session.lookupDest( address, timeout );
			
			ok = result != null;
			
			return( result );
		
		}finally{
			
			//System.out.println( "Session: " + address + " - " + ok + " - " + ( SystemTime.getMonotonousTime() - start ));
		}
	}
	
	public Destination
	lookupAddress(
		String				address,
		I2PHelperAdapter	adapter )
	
		throws UnknownHostException
	{
		Destination remote_dest = null;

		try{
			if ( address.length() < 400 ){
				
				if ( !address.endsWith( ".i2p" )){
				
					address += ".i2p";
				}
			}
						
			boolean b32_address = address.endsWith( ".b32.i2p" );
			
			I2PAppContext ctx = router.getContext();
			
			NamingService name_service = ctx.namingService();
	
			if ( name_service != null && !b32_address ){
					
				// long start = SystemTime.getMonotonousTime();
				
				remote_dest = name_service.lookup( address );
			
				// System.out.println( "NameService: " + address + " - " + (remote_dest!=null) + " - " + ( SystemTime.getMonotonousTime() - start ));

			}else{
				
				remote_dest = new Destination();
	       
				try{
					remote_dest.fromBase64( address );
					
				}catch( Throwable e ){
					
					remote_dest = null;
				}
			}
				
			if ( remote_dest == null ){
				
				if ( b32_address ){
					
					remote_dest = lookupDest( address, 30*1000 );
					
				}else{
					
					String address_str = adapter.lookup( address );
				
					if ( address_str != null ){
						
						if ( address_str.length() < 400 ){
							
							remote_dest = lookupDest( address_str, 30*1000 );
							
						}else{
							
							remote_dest = new Destination();
						       
							try{
								remote_dest.fromBase64( address_str );
								
							}catch( Throwable e ){
								
								remote_dest = null;
							}
						}
					}
					
					if ( remote_dest == null ){
						
							// only makes sense to lookup non-b32 host names via lookupDest when router not running
							// in this JVM (otherwise ctx.namingService().lookup will already have done this job)
						
						if ( !ctx.isRouterContext()){
						
							remote_dest = lookupDest( address, 30*1000 );
						}
					}
				}
			}
		}catch( Throwable e ){
			
		}finally{
				
			//System.out.println( "lookupAddress( " + address + " ) -> " + remote_dest );
		}
		
		if ( remote_dest != null ){
			
			return( remote_dest );
		}
		
		throw( new UnknownHostException( address ));
	}
	
	public I2PSocketOptions
	buildOptions(
		Properties	options )
	
		throws Exception
	{
		return( socket_manager.buildOptions( options ));
	}
	
	public void
	updateOptions(
		Properties		options )
	{
		updated_options = options;

		if ( !isSessionClosedSupport()){

			session.updateOptions( options );
		}
	}
	
	public byte[]
	makeI2PDatagram(
		byte[]	payload )
	{		
		if ( isSessionClosedSupport()){

			return( null );
			
		}else{
			
			I2PDatagramMaker dgMaker = new I2PDatagramMaker( session );
	        
	        payload = dgMaker.makeI2PDatagram( payload );
	        
	        return( payload );
		}
	}
	
	public boolean
	sendMessage(
		Destination			dest,
		byte[]				payload,
		int					offset,
		int					size,
		int					proto,
		int					fromPort,
		int					toPort,
		SendMessageOptions	options )
	
		throws Exception
	{
		isSessionClosedSupport( true );

		return( session.sendMessage( dest, payload, offset, size, proto, fromPort, toPort, options ));
	}
	
	public I2PSocket
	connect(
		Destination			dest,
		I2PSocketOptions	opts )
	
		throws Exception
	{
		isSessionClosedSupport( true );

		return( socket_manager.connect( dest, opts ));
	}
	
	public I2PSocket
	accept()
	
		throws Exception
	{
		isSessionClosedSupport( true );

		I2PSocketManager sm = socket_manager;
		
		if ( server_socket == null ){
		
			server_socket = sm.getServerSocket();
		}
		
		try{
			return( server_socket.accept());
			
		}catch( RouterRestartException e ){
			
			server_socket.close();
			
			server_socket = null;
			
			sm.destroySocketManager();
			
			throw( e );
		}
	}
	
	public void
	addMuxedSessionListener(
		I2PSessionMuxedListener		l,
		int							proto,
		int							port )
	{
		synchronized( this ){
			
			muxer_listeners.add( new Object[]{ l,proto, port });
		
			session.addMuxedSessionListener( l, proto, port );
		}
	}
		
	public void
	destroy()
	{
		synchronized( this ){
			
			destroyed = true;
		}
		
		try{

			if ( socket_manager != null ){
				
				socket_manager.destroySocketManager();
				
				socket_manager = null;
			}
		}catch( Throwable e ){
			
			e.printStackTrace();
		}
		
		try{
			if ( session != null ){
				
				session.destroySession();
				
				session = null;
			}
		}catch( Throwable e ){
			
			e.printStackTrace();
		}
		
		try{
			if ( server_socket != null ){
				
				server_socket.close();
				
				server_socket = null;
			}
		}catch( Throwable e ){
			
			e.printStackTrace();
		}
		
		if ( timer_event != null ){
			
			timer_event.cancel();
		}
	}
}
