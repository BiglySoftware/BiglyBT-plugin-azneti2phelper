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
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.ThreadPool;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.impl.MessageInputStream;
import net.i2p.client.streaming.impl.MessageInputStream.ActivityListener;

public class 
I2PHelperSocketForwarder
{
		// read pool is basically non-blocking so doesn't need to be large
	
	private static ThreadPool			async_read_pool 	= new ThreadPool( "I2PSocket forward read", 32, true );
	
		// write pool is blocking as there is no non-blocking support for writes to I2P
	
	private static ThreadPool			async_write_pool 	= new ThreadPool( "I2PSocket forward write", 256, true );

	private VirtualChannelSelector	read_selector;
	private VirtualChannelSelector	write_selector;

	private Set<ForwardingActivity>	activities = new HashSet<>();
	
	private TimerEvent			tidy_event;
	
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
				
				AESemaphore sem = new AESemaphore( "init" );
				
				new AEThread2( "I2PForwarder:read"){
					
						@Override
						public void
						run()
						{
							int	ac = activation_count;
							
							sem.release();
							
							selectLoop( ac, read_selector );
						}
					}.start();

				write_selector 		= new VirtualChannelSelector( "I2PForwarder:write", VirtualChannelSelector.OP_WRITE, true );

				new AEThread2( "I2PForwarder:write"){
					
						@Override
						public void
						run()
						{
							int	ac = activation_count;
							
							sem.release();
							
							selectLoop( ac, write_selector );
						}
					}.start();
				
				sem.reserve();
				sem.reserve();
			}
			
			activity = new ForwardingActivityI2P( i2p_socket, bigly_socket, on_complete );
			
			activities.add( activity );
			
			if ( tidy_event != null ){
				
				tidy_event.cancel();
				
				tidy_event = null;
			}
		}
		
		activity.start();
	}
	
	protected void
	forward(
		Socket		tor_socket,
		Socket		bigly_socket,
		Runnable	on_complete )
	
		throws Exception
	{
		ForwardingActivity activity;
		
		synchronized( this ){
		
			if ( read_selector == null ){
				
				read_selector	 	= new VirtualChannelSelector( "I2PForwarder:read", VirtualChannelSelector.OP_READ, false );
				
				AESemaphore sem = new AESemaphore( "init" );
				
				new AEThread2( "I2PForwarder:read"){
					
						@Override
						public void
						run()
						{
							int	ac = activation_count;
							
							sem.release();
							
							selectLoop( ac, read_selector );
						}
					}.start();

				write_selector 		= new VirtualChannelSelector( "I2PForwarder:write", VirtualChannelSelector.OP_WRITE, true );

				new AEThread2( "I2PForwarder:write"){
					
						@Override
						public void
						run()
						{
							int	ac = activation_count;
							
							sem.release();
							
							selectLoop( ac, write_selector );
						}
					}.start();
				
				sem.reserve();
				sem.reserve();
			}
			
			activity = new ForwardingActivityTor( tor_socket, bigly_socket, on_complete );
			
			activities.add( activity );
			
			if ( tidy_event != null ){
				
				tidy_event.cancel();
				
				tidy_event = null;
			}
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
						
					if ( tidy_event != null ){
						
						tidy_event.cancel();
					}
					
					tidy_event = 
						SimpleTimer.addEvent(
							"tidy",
							SystemTime.getOffsetTime( 30*1000 ),
							new TimerEventPerformer(){
								
								@Override
								public void perform(TimerEvent event){
								
									synchronized( I2PHelperSocketForwarder.this ){
										
										if ( activities.isEmpty()){
											
											read_selector = null;
											
											write_selector = null;
											
												// this will destroy the selectors
											
											activation_count++;
										}
									}
								}
							});
						
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

		try{
			while( !destroyed && activation_count == ac ){
	
				try{
					selector.select( 100 );
	
						// only use one selector to trigger the timeouts!
	
					if ( selector == read_selector ){
	
						long	now = SystemTime.getMonotonousTime();
	
						if ( now - last_time >= 30*1000 ){
	
							last_time	= now;
	
							checkTimeouts( now );
						}
					}
				}catch( Throwable e ){
	
					Debug.printStackTrace(e);
				}
			}
		}finally{
			
			selector.destroy();
			
				// we have to run one final 'select' operation to destroy the selector...
			
			selector.select( 100 );
		}
	}
	
	public void
	checkTimeouts(
		long	now )
	{
		List<ForwardingActivity>	to_destroy = new ArrayList<>();
		
		synchronized( this ){
			
			for ( ForwardingActivity a: activities ){
				
				if ( a.timeout()){
					
					to_destroy.add( a );
				}
			}
		}
		
		for ( ForwardingActivity a: to_destroy ){
			
			a.destroy();
		}
	}
	
	void 
	destroy()
	{
		synchronized( this ){
			
			destroyed = true;
						
			read_selector 	= null;
			write_selector	= null;
			
				// this will destroy the selectors
			
			activation_count++;
						
			for ( ForwardingActivity a: activities ){
					
				a.destroy();
			}
		}
	}
	
	interface
	ForwardingActivity
	{
		public void
		start()
		
			throws Exception;
		
		public boolean
		timeout();
		
		public void
		destroy();
	}
	
	class
	ForwardingActivityI2P
		implements ForwardingActivity
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
		
		private long		last_activity	= SystemTime.getMonotonousTime();
		
		private boolean		failed;
		private boolean		destroyed;
		
		protected
		ForwardingActivityI2P(
			I2PSocket	_i2p_socket,
			Socket		_bigly_socket,
			Runnable	_on_complete )
		{
			i2p_socket		= _i2p_socket;
			bigly_socket 	= _bigly_socket;
			on_complete		= _on_complete;
		}
		
		public void
		start()
		
			throws Exception
		{
			// System.out.println( "Forwarder start: " + i2p_socket.getPeerDestination());
			
			bigly_channel = bigly_socket.getChannel();
			
			bigly_channel.configureBlocking( false );
				
			i2p_input_stream	= (MessageInputStream)i2p_socket.getInputStream();
			i2p_output_stream	= i2p_socket.getOutputStream();
				
			i2p_input_stream.setReadTimeout( 0 );	// non-blocking
			
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
 	        			last_activity	= SystemTime.getMonotonousTime();
 	        			
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
						last_activity	= SystemTime.getMonotonousTime();
						
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
				
					write_selector_registered = true;
					
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
								
								last_activity	= SystemTime.getMonotonousTime();
								
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
		
		public boolean
		timeout()
		{
			try{
				if ( i2p_socket.isClosed() || bigly_socket.isClosed()){
					
					return( true );
				}
			}catch( Throwable e ){
			}
			
			if ( SystemTime.getMonotonousTime() - last_activity > 2*60*1000 ){
				
				return( true );
			}
			
			return( false );
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
		
		public void
		destroy()
		{
			synchronized( lock ){
				
				if ( destroyed ){
					
					return;
				}
				
				destroyed = true;
			}
			
			// System.out.println( "Forwarder end: " + i2p_socket.getPeerDestination());


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
	
	class
	ForwardingActivityTor
		implements ForwardingActivity
	{
		final private Socket		tor_socket;
		final private Socket		bigly_socket;
		final private Runnable		on_complete;

		private SocketChannel	tor_channel;
		private SocketChannel	bigly_channel;
		
		private VirtualChannelSelector.VirtualSelectorListener	tor_listener; 
		private VirtualChannelSelector.VirtualSelectorListener	bigly_listener; 
		
		private boolean 	bigly_write_selector_registered;
		private boolean 	bigly_read_selector_registered;
		private boolean 	tor_write_selector_registered;
		private boolean 	tor_read_selector_registered;
		
		private volatile ByteBuffer		bigly_output_buffer;;
		private volatile ByteBuffer		tor_output_buffer;;
		
		private Object		lock = new Object();
		
		private long		last_activity	= SystemTime.getMonotonousTime();
		
		private boolean		failed;
		private boolean		destroyed;
		
		protected
		ForwardingActivityTor(
			Socket		_tor_socket,
			Socket		_bigly_socket,
			Runnable	_on_complete )
		{
			tor_socket		= _tor_socket;
			bigly_socket 	= _bigly_socket;
			on_complete		= _on_complete;
		}
		
		public void
		start()
		
			throws Exception
		{
			// System.out.println( "Forwarder start: " + i2p_socket.getPeerDestination());
			
			bigly_channel = bigly_socket.getChannel();
			
			bigly_channel.configureBlocking( false );
				
			tor_channel = tor_socket.getChannel();
			
			tor_channel.configureBlocking( false );

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
 	        			last_activity	= SystemTime.getMonotonousTime();
 	        			
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

				tor_listener =
		        	new VirtualChannelSelector.VirtualSelectorListener()
					{
	 	        		@Override
				        public boolean
						selectSuccess(
							VirtualChannelSelector 	selector,
							SocketChannel 			sc,
							Object 					attachment )
		        		{
	 	        			last_activity	= SystemTime.getMonotonousTime();
	 	        			
		        			try{
		        				if ( selector == read_selector ){
		        					
		        					return( readFromTor());
		        					
		        				}else if ( selector == write_selector ){
		        					
		        					return( writeToTor());
		        					
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

    			// set up read inputs
    			
			readFromBigly();
			
			readFromTor();
		}
	
		protected boolean
		writeToBigly()
		
			throws IOException
		{						
			int written = bigly_channel.write( bigly_output_buffer );
					
 			//System.out.println( "bigly-write: " + written );

			if ( bigly_output_buffer.hasRemaining()){
			
				if ( bigly_write_selector_registered ){
					
					write_selector.resumeSelects( bigly_channel );
					
				}else{
				
					bigly_write_selector_registered = true;
					
					write_selector.register( bigly_channel, bigly_listener, null  );
				}
			}else{
				
				synchronized( lock ){
			
					bigly_output_buffer	= null;
					
					readFromTor();
				}
			}
			
			return( written > 0 );
		}
		
		protected boolean
		readFromBigly()
		
			throws IOException
		{
			if ( tor_output_buffer != null ){
				
				Debug.out( "tor_output_buffer must be null" );
				
				throw( new IOException( "Inconsistent" ));
			}
			
			tor_output_buffer = ByteBuffer.allocate( 32*1024 );
			
  			int read = bigly_channel.read( tor_output_buffer );
  			
 			//System.out.println( "bigly-read: " + read );

			if ( read == 0 ){

				tor_output_buffer = null;
				
				if ( bigly_read_selector_registered ){
					
					read_selector.resumeSelects( bigly_channel );
				}else{
					
					bigly_read_selector_registered = true;
					
					read_selector.register( bigly_channel, bigly_listener, null  );
				}
			}else if ( read > 0 ){
				
				read_selector.pauseSelects( bigly_channel );
				
				tor_output_buffer.flip();
				
				writeToTor();
		
			}else{
				
				throw( new IOException( "End of stream" ));
			}
			
			return( read > 0 );
		}
		
		protected boolean
		writeToTor()
		
			throws IOException
		{						
			int written = tor_channel.write( tor_output_buffer );
					
 			//System.out.println( "tor-write: " + written );
 			
			if ( tor_output_buffer.hasRemaining()){
			
				if ( tor_write_selector_registered ){
					
					write_selector.resumeSelects( tor_channel );
					
				}else{
				
					tor_write_selector_registered = true;
					
					write_selector.register( tor_channel, tor_listener, null  );
				}
			}else{
				
				synchronized( lock ){
			
					tor_output_buffer	= null;
					
					readFromBigly();
				}
			}
			
			return( written > 0 );
		}
		
		protected boolean
		readFromTor()
		
			throws IOException
		{
			if ( bigly_output_buffer != null ){
				
				Debug.out( "bigly_output_buffer must be null" );
				
				throw( new IOException( "Inconsistent" ));
			}
			
			bigly_output_buffer = ByteBuffer.allocate( 32*1024 );
			
  			int read = tor_channel.read( bigly_output_buffer );
  			
  			//System.out.println( "tor-read: " + read );
  			
			if ( read == 0 ){

				bigly_output_buffer = null;
				
				if ( tor_read_selector_registered ){
					
					read_selector.resumeSelects( tor_channel );
					
				}else{
					
					tor_read_selector_registered = true;
					
					read_selector.register( tor_channel, tor_listener, null  );
				}
			}else if ( read > 0 ){
				
				read_selector.pauseSelects( tor_channel );
				
				bigly_output_buffer.flip();
				
				writeToBigly();
		
			}else{
				
				throw( new IOException( "End of stream" ));
			}
			
			return( read > 0 );
		}
		
		
		public boolean
		timeout()
		{
			try{
				if ( tor_socket.isClosed() || bigly_socket.isClosed()){
					
					return( true );
				}
			}catch( Throwable e ){
			}
			
			if ( SystemTime.getMonotonousTime() - last_activity > 2*60*1000 ){
				
				return( true );
			}
			
			return( false );
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
		
		public void
		destroy()
		{
			synchronized( lock ){
				
				if ( destroyed ){
					
					return;
				}
				
				destroyed = true;
			}
			
			// System.out.println( "Forwarder end: " + i2p_socket.getPeerDestination());


			if ( bigly_read_selector_registered ){
				
				read_selector.cancel( bigly_channel );
				
				bigly_read_selector_registered = false;
			}
			
			if ( bigly_write_selector_registered ){
				
				write_selector.cancel( bigly_channel );
				
				bigly_write_selector_registered = false;
			}
			
			if ( tor_read_selector_registered ){
				
				read_selector.cancel( tor_channel );
				
				tor_read_selector_registered = false;
			}
			
			if ( tor_write_selector_registered ){
				
				write_selector.cancel( tor_channel );
				
				tor_write_selector_registered = false;
			}
			
			try{
				tor_socket.close();
				
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
