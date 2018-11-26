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

package org.parg.azureus.plugins.networks.i2p;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.*;

import com.biglybt.core.networkmanager.VirtualChannelSelector;
import com.biglybt.core.util.AERunnable;
import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.AEThread2;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.ThreadPool;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.impl.MessageInputStream;
import net.i2p.client.streaming.impl.MessageInputStream.ActivityListener;

public class 
I2PHelperSocketForwarder
{
	private static ThreadPool			async_read_pool 	= new ThreadPool( "I2PSocket forward read", 10, true );
	private static ThreadPool			async_write_pool 	= new ThreadPool( "I2PSocket forward write", 10, true );

	private VirtualChannelSelector	read_selector;
	private VirtualChannelSelector	write_selector;

	private List<ForwardingActivity>	activities = new ArrayList<>();
	
	private volatile int		activation_count;
	
	private volatile boolean destroyed;
	
	I2PHelperSocketForwarder()
	{		
	}
	
	protected void
	forward(
		I2PSocket	i2p_socket,
		Socket		bigly_socket,
		Runnable	on_complete )
	
		throws Exception
	{
		ForwardingActivity activity;
		
		synchronized( this ){
		
			if ( read_selector == null ){
				
				read_selector	 	= new VirtualChannelSelector( "I2PForwarder:read", VirtualChannelSelector.OP_READ, false );
				write_selector 		= new VirtualChannelSelector( "I2PForwarder:write", VirtualChannelSelector.OP_WRITE, true );
				
				AESemaphore sem = new AESemaphore( "init" );
				
				AEThread2	read_thread =
					new AEThread2( "I2PForwarder:read")
					{
						@Override
						public void
						run()
						{
							int	ac = activation_count;
							
							sem.release();
							
							selectLoop( ac, read_selector );
						}
					};

				read_thread.start();

				AEThread2	write_thread =
					new AEThread2( "I2PForwarder:write")
					{
						@Override
						public void
						run()
						{
							int	ac = activation_count;
							
							sem.release();
							
							selectLoop( ac, write_selector );
						}
					};

				write_thread.start();
				
				sem.reserve();
				sem.reserve();
			}
			
			activity = new ForwardingActivity( i2p_socket, bigly_socket, on_complete );
			
			activities.add( activity );
		}
		
		activity.start();
	}
	
	private void
	destroyed(
		ForwardingActivity		a )
	{
		synchronized( this ){

			if ( !destroyed ){
				
				activities.remove( a );
				
				if ( activities.isEmpty()){
					
					read_selector.destroy();
					
					read_selector = null;
					
					write_selector.destroy();
					
					write_selector = null;
					
					activation_count++;
				}
			}
		}
	}
	
	protected void
	selectLoop(
		int						ac,
		VirtualChannelSelector	selector )
	{
		long	last_time	= 0;

		while( !destroyed && activation_count == ac ){

			try{
				selector.select( 100 );

					// only use one selector to trigger the timeouts!

				if ( selector == read_selector ){

					long	now = SystemTime.getCurrentTime();

					if ( now < last_time ){

						last_time	= now;

					}else if ( now - last_time >= 10*1000 ){

						last_time	= now;

						checkTimeouts( now );
					}
				}
			}catch( Throwable e ){

				Debug.printStackTrace(e);
			}
		}
	}
	
	public void
	checkTimeouts(
		long	now )
	{
		/*
		synchronized( this ){
			
			System.out.println( "active=" + activities.size());
			
			for ( ForwardingActivity a: activities ){
				
				System.out.println( "    " + a.getString());
			}
		}
		*/
	}
	
	void 
	destroy()
	{
		synchronized( this ){
			
			destroyed = true;
			
			read_selector.destroy();
			
			write_selector.destroy();
			
			read_selector 	= null;
			write_selector	= null;
			
			activation_count++;
						
			for ( ForwardingActivity a: activities ){
					
				a.destroy();
			}
		}
	}
	
	class
	ForwardingActivity
	{
		final private I2PSocket		i2p_socket;
		final private Socket		bigly_socket;
		final private Runnable		on_complete;

		private SocketChannel	bigly_channel;
		
		private VirtualChannelSelector.VirtualSelectorListener	bigly_listener; 
		
		private boolean 	write_selector_registered;
		private boolean 	read_selector_registered;
		
		private MessageInputStream 	i2p_input_stream;
		private OutputStream		i2p_output_stream;
		
		private byte[]					i2p_input_buffer = new byte[16*1024];
		private volatile ByteBuffer		bigly_output_buffer;;

		private byte[]					i2p_output_buffer = new byte[16*1024];
		private volatile ByteBuffer		bigly_input_buffer;

		private boolean		i2p_read_active;
		private boolean		i2p_read_deferred;
		private boolean 	i2p_read_dead;
		
		private Object		lock = new Object();
		
		private boolean		failed;
		private boolean		destroyed;
		
		protected
		ForwardingActivity(
			I2PSocket	_i2p_socket,
			Socket		_bigly_socket,
			Runnable	_on_complete )
		{
			i2p_socket		= _i2p_socket;
			bigly_socket 	= _bigly_socket;
			on_complete		= _on_complete;
		}
		
		void
		start()
		
			throws Exception
		{
			bigly_channel = bigly_socket.getChannel();
			
			bigly_channel.configureBlocking( false );
				
			i2p_input_stream	= (MessageInputStream)i2p_socket.getInputStream();
			i2p_output_stream	= i2p_socket.getOutputStream();
							
			bigly_listener =
	        	new VirtualChannelSelector.VirtualSelectorListener()
				{
 	        		@Override
			        public boolean
					selectSuccess(
						VirtualChannelSelector 	selector,
						SocketChannel 			sc,
						Object 					attachment )
	        		{
	        			try{
	        				if ( selector == read_selector ){
	        					
	        					return( readFromBigly());
	        					
	        				}else if ( selector == write_selector ){
	        					
	        					return( writeToBigly());
	        					
	        				}else{
	        					
	        					throw( new Exception( "Selector is dead" ));
	        				}

	              		}catch( Throwable e ){

	            			failed( e );

	            			return( false );
	            		}
	        		}

	        		@Override
			        public void
					selectFailure(
						VirtualChannelSelector 	selector,
						SocketChannel 			sc,
						Object 					attachment,
						Throwable 				msg )
	        		{
	        			failed( msg );
	        		}
				};

    	    				
    			// set up read input from bigly 
    			
			readFromBigly();
				
    	
    			// set up read input from I2P
    		
			i2p_input_stream.setActivityListener( 
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

		private void
		readFromI2P()
		{
			synchronized( lock ){
				
				if ( i2p_read_dead ){
					
					return;
				}
				
				if ( i2p_read_active || bigly_output_buffer != null ){
					
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
						readFromI2PSupport();
					}
				});
		}
		
		private void
		readFromI2PSupport()
		{
			boolean	went_async = false;

			try{											
				while( !i2p_socket.isClosed()){
				
					int	len = i2p_input_stream.read( i2p_input_buffer );
					
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
																					
					if ( bigly_output_buffer != null ){
						
						Debug.out("bigly_output_buffer must be null" );
						
						throw( new IOException( "Inconsistent" ));
					}
														
					bigly_output_buffer = ByteBuffer.wrap( i2p_input_buffer, 0, len );
					
					writeToBigly();
					
					if ( bigly_output_buffer != null ){
						
						went_async = true;
						
						return;
					}
				}											
			}catch( Throwable e ){
					
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
				
				failed( e );
				
			}finally{
			
				synchronized( lock ){
					
					i2p_read_active = false;
					
					if ( !went_async ){
						
						i2p_read_dead = true;
					}
					
					if ( i2p_read_deferred ){
						
						i2p_read_deferred = false;
						
						readFromI2P();
					}
				}
			}
		}
		
		
		protected boolean
		writeToBigly()
		
			throws IOException
		{						
			int written = bigly_channel.write( bigly_output_buffer );
																
			if ( bigly_output_buffer.hasRemaining()){
			
				if ( write_selector_registered ){
					
					write_selector.resumeSelects( bigly_channel );
					
				}else{
				
					write_selector.register( bigly_channel, bigly_listener, null  );
				}
			}else{
				
				synchronized( lock ){
			
					bigly_output_buffer	= null;
					
					readFromI2P();
				}
			}
			
			return( written > 0 );
		}
		
		protected boolean
		readFromBigly()
		
			throws IOException
		{
			if ( bigly_input_buffer != null ){
				
				Debug.out( "bigly_input_buffer must be null" );
				
				throw( new IOException( "Inconsistent" ));
			}
			
			bigly_input_buffer = ByteBuffer.wrap( i2p_output_buffer );
			
  			int read = bigly_channel.read( bigly_input_buffer );
  			
			if ( read == 0 ){

				bigly_input_buffer = null;
				
				if ( !read_selector_registered ){
						
					read_selector_registered = true;
					
					read_selector.register( bigly_channel, bigly_listener, null  );
				}
			}else if ( read > 0 ){
				
				read_selector.pauseSelects( bigly_channel );
				
				async_write_pool.run(
					new AERunnable()
					{
						@Override
						public void
						runSupport()
						{	
							boolean	ok = false;
							
							try{
								i2p_output_stream.write( i2p_output_buffer, 0, read );
								
								// i2p_output_stream.flush();
								
								ok = true;
								
							}catch( Throwable e ){
								
								failed( e );
								
							}finally{
								
								bigly_input_buffer = null;
								
								if ( ok ){
									
									read_selector.resumeSelects( bigly_channel );
								}
							}
						}
					});
			}else{
				
				throw( new IOException( "End of stream" ));
			}
			
			return( read > 0 );
		}
		
		private void
		failed(
			Throwable	e )
		{
			//e.printStackTrace();
			
			synchronized( lock ){
				
				if ( failed ){
					
					return;
				}
				
				failed = true;
			}
			
			destroy();
		}
		
		private void
		destroy()
		{
			synchronized( lock ){
				
				if ( destroyed ){
					
					return;
				}
				
				destroyed = true;
			}

			if ( read_selector_registered ){
				
				read_selector.cancel( bigly_channel );
				
				read_selector_registered = false;
			}
			
			if ( write_selector_registered ){
				
				write_selector.cancel( bigly_channel );
				
				write_selector_registered = false;
			}
			
			try{
				i2p_socket.close();
				
			}catch( Throwable e ){
			}
			
			try{
				bigly_socket.close();
				
			}catch( Throwable e ){
			}
			
				// twice to match old implementation
			
			on_complete.run();
			on_complete.run();
			
			destroyed( this );
		}
		
		/*
		private String
		getString()
		{
			return(
				"read=" + read_selector.isRegistered( bigly_channel ) + "/" + read_selector.isPaused( bigly_channel ) + ", " +
				"write=" + write_selector.isRegistered( bigly_channel ) + "/" + write_selector.isPaused( bigly_channel )  + ", " +
				"buff=" + bigly_input_buffer + "/" + bigly_output_buffer + ", " +
				"state=" + i2p_read_active + "/" + i2p_read_deferred + "/" + i2p_read_dead + "/" + failed + "/" + destroyed );
		}
		*/
	}
}
