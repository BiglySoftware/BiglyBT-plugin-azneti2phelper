/*
 * Created on Jun 12, 2014
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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

import com.biglybt.core.util.*;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.Base32;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.router.Router;

import org.parg.azureus.plugins.networks.i2p.I2PHelperAdapter;
import org.parg.azureus.plugins.networks.i2p.I2PHelperDHT;
import org.parg.azureus.plugins.networks.i2p.snarkdht.NID;
import org.parg.azureus.plugins.networks.i2p.snarkdht.NodeInfo;
import org.parg.azureus.plugins.networks.i2p.vuzedht.DHTI2P;

public class 
I2PHelperRouterDHT 
{
	private final I2PHelperRouter			router;
	private final File						config_dir;
	private final int						dht_index;
	private final boolean					is_secondary;
	
	private final boolean					is_bootstrap_node;
	private final boolean					is_vuze_dht;
	private final boolean					force_new_address;
	private final I2PHelperAdapter			adapter;
	
	private volatile I2PSMHolder			sm_holder;
	private volatile Properties				dht_socket_manager_properties;

	private String				b32_dest = "";
	
	private I2PHelperDHT		dht;

	private Object				init_lock	= new Object();
	
	private volatile boolean	enabled;
	private volatile boolean	started;
	private volatile boolean	initialized;
	private volatile boolean	destroyed;

	private AESemaphore			init_sem = new AESemaphore( "I2PHelperRouterDHT" );
	
	protected
	I2PHelperRouterDHT(
		I2PHelperRouter			_router,
		File					_config_dir,
		int						_dht_index,
		boolean					_is_secondary,
		boolean					_is_bootstrap_node,
		boolean					_is_vuze_dht,
		boolean					_force_new_address,
		I2PHelperAdapter		_adapter )
	{
		router				= _router;
		config_dir			= _config_dir;
		dht_index			= _dht_index;
		is_secondary		= _is_secondary;
		is_bootstrap_node	= _is_bootstrap_node;
		is_vuze_dht			= _is_vuze_dht;
		force_new_address	= _force_new_address;
		adapter				= _adapter;	
		
		enabled	= true;
	}
	
	public String
	getName()
	{
		String name;
		
		if ( dht_index == I2PHelperRouter.DHT_MIX ){
			name = "Mix";
		}else if ( dht_index == I2PHelperRouter.DHT_NON_MIX ){
			name = "Pure";
		}else{
			name = String.valueOf( dht_index );
		}
		
		return( "[" + name + "/" + (is_vuze_dht?"AZ":"I2P") + "]" + (is_secondary?"+":"" ));
	}
	
	private void
	log(
		String		str )
	{
		adapter.log( getName() + ": " + str );
	}
	
	public void
	setEnabled(
		boolean		b )
	{
		enabled	= b;
	}
	
	public boolean
	isEnabled()
	{
		return( enabled );
	}
	
	public int
	getDHTIndex()
	{
		return( dht_index );
	}
	
	public boolean
	isSecondary()
	{
		return( is_secondary );
	}
	
	public boolean
	isDHTStarted()
	{
		return( started );
	}
	
	public boolean
	isDHTInitialised()
	{
		return( initialized );
	}
	
	protected void 
	initialiseDHT(
		String			i2p_host,
		int				i2p_port,
		String			name,
		Properties		_sm_properties )
		
		throws Exception
	{
		if ( !enabled ){
			
			throw( new Exception( "DHT " + dht_index + " is not enabled" ));
		}
		
		started = true;
		
		Properties sm_properties = new Properties();
		
		sm_properties.putAll( _sm_properties );
		
		router.setupSMExplicitOpts( sm_properties, Constants.APP_NAME + ": DHT " + name + (is_secondary?"+":""), dht_index==0?I2PHelperRouter.SM_TYPE_MIX:I2PHelperRouter.SM_TYPE_PURE );

		try{
			synchronized( init_lock ){
			
				if ( destroyed ){
					
					throw( new Exception( "DHT destroyed" ));
				}
				
				if ( initialized  ){
					
					return;
				}
			
				router.waitForInitialisation();
				
				try{
					long start = SystemTime.getMonotonousTime();
					
					log( "Initializing DHT..." );
									
					String suffix = dht_index==0?"":String.valueOf(dht_index);
					
					if ( is_secondary ){
						
						suffix += "_s";
					}
					
					File dht_config 	= new File( config_dir,  "dht"+suffix+".config" );
					File dest_key_file 	= new File( config_dir,  "dest_key"+suffix+".dat" );
			        
			        boolean[]	use_existing_key = { dest_key_file.exists() && !force_new_address };
			        
			        String f_suffix = suffix;
			        
			        sm_holder = 
						new I2PSMHolder( router )
						{
							@Override
							protected String 
							getName()
							{
								return(  "dht"+f_suffix );
							}
						
							@Override
							protected I2PSocketManager 
							createSocketManager(
								boolean		recovering )
							
								throws Exception
							{
						        boolean	tried_new_key = false;
						        
						        I2PSocketManager	sm = null;
				
						        while( true ){
						        			
						        	if ( isReady()){
						        									        	
							        	try{
											if ( recovering || use_existing_key[0] ){
									         	
									    		InputStream is = new FileInputStream( dest_key_file );
									    	
									    		try{
									    			sm = I2PSocketManagerFactory.createDisconnectedManager( is, i2p_host, i2p_port, sm_properties );
									    	
									    			sm.getSession().connect();
									    			
									    		}finally{
									    		
									    			is.close();
									    		}
									        }else{
									        	
									        	sm = I2PSocketManagerFactory.createDisconnectedManager( null, i2p_host, i2p_port, sm_properties );
									        	
									        	sm.getSession().connect();
									        }
							        	}catch( Throwable e ){
							        		
							        		if ( sm != null ){
							        			
							        			sm.destroySocketManager();
							        			
							        			sm = null;
							        		}
							        		
							        		Debug.out( "Failed to create socket manager", e );
							        	}
						        	}
						        	
									if ( sm != null ){
										
										dht_socket_manager_properties = new Properties();
										
										dht_socket_manager_properties.putAll( sm_properties );
										
										break;
									
									}else{
										
											// I've seen timeouts with 3 mins, crank it up
										
										if ( SystemTime.getMonotonousTime() - start > 15*60*1000 ){
											
												// Seen borked key files causing us to get here as well
												// delete key file and try once more
											
											if ( !tried_new_key && !recovering ) {
												
												log( "Forcing new key" );
												
												tried_new_key = true;
												
												use_existing_key[0] = false;
												
											}else{
												
												throw( new Exception( "Timeout creating socket manager" ));
											}
										}
										
										Thread.sleep( 5000 );
													
										if ( destroyed ){
											
											throw( new Exception( "Server destroyed" ));
										}
									}
						        }
						        
						        return( sm );
							}
							
							@Override
							protected I2PSession
							getSession(
								I2PSocketManager		sm )
							
								throws Exception
							{
								log( "Waiting for socket manager startup" );
								
								while( true ){
									
									if ( destroyed ){
										
										sm.destroySocketManager();
										
										throw( new Exception( "DHT destroyed" ));
									}
									
									I2PSession dht_session = sm.getSession();
									
									if ( dht_session != null ){
										
										return( dht_session );
									}
									
									Thread.sleep(250);
								}
							}
							
							@Override
							protected boolean 
							isReady()
							{
								Router r = router.getRouter();
					        	
					        	return( r == null || r.getContext().clientManager().isAlive());
							}
							
							@Override
							protected void 
							logMessage(
								String str )
							{
								log( str );
							}};
					
					log( "Socket manager startup complete - elapsed=" + (SystemTime.getMonotonousTime() - start ));
					
					Destination my_dest = sm_holder.getMyDestination();
					
					String	full_dest 	= my_dest.toBase64() + ".i2p";
								
					b32_dest	= Base32.encode( my_dest.calculateHash().getData()) + ".b32.i2p";
					
					adapter.stateChanged( this, false );
										
					new AEThread2( "I2P:accepter (dht)" )
					{
						@Override
						public void
						run()
						{
							while( !destroyed ){
								
								try{
									I2PSocket socket = sm_holder.accept();
									
									if ( socket == null ){
										
										if ( destroyed ){
											
											break;
											
										}else{
											
											Thread.sleep(500);
										}
									}else{
										try{
										
											adapter.incomingConnection( I2PHelperRouterDHT.this, socket );
										
										}catch( Throwable e ){
											
											Debug.out( e );
											
											try{
												socket.close();
												
											}catch( Throwable f ){
											}
										}
									}
								}catch( Throwable e ){
									
									if ( !destroyed ){
										
										if ( sm_holder.isReady()){
										
											if ( !Debug.getNestedExceptionMessage(e).toLowerCase(Locale.US).contains( "closed" )){
										
												Debug.out( e );
											}
										}
										
										try{
											Thread.sleep(2500);
											
										}catch( Throwable f ){
										}
									}
								}
							}
						}
					}.start();
									
					if ( is_secondary ){
						
						if ( !use_existing_key[0] ){
							
							sm_holder.writePublicKey( dest_key_file );
						}
						
						if ( destroyed ){
							
							throw( new Exception( "Router destroyed" ));
						}

						log( "MyDest+: " + full_dest );
						log( "        " + b32_dest  + ", existing=" + use_existing_key[0] );

					}else{
						
						Properties dht_props;
						
						if ( !use_existing_key[0] ){
							
							sm_holder.writePublicKey( dest_key_file );
							
							dht_props = new Properties();
							
						}else{
						
							dht_props = I2PHelperUtils.readProperties( dht_config );
						}
						
						String dht_port_str = dht_props.getProperty( "port" );
						String dht_NID_str 	= dht_props.getProperty( "nid" );
							
						boolean	use_existing_nid = dht_port_str != null && dht_NID_str != null;
							
						
						String 	boot_dest 	= "N0e4jfsxy~NYzyr-0bY1nwpnhTza8fn1wWr6IHHOmaIEnbEvgltJvyJn8LWvwlu589mUPhQXQb9BtMrkEan8RZSL4Vo2iFgMCxjTOnfA2dW1~JpL0ddGM28OQITya-1YDgNZFmyX0Me-~RjJjTg31YNozDoosIQ-Uvz2s5aUrzI0gt0r3M4PFUThb0eefd51Yb-eEQMpBb-Hd~EU07yw46ljy2uP4tiEPlWt0l0YR8nbeH0Eg6i3fCoSVgWpSeRjJ9vJeHvwGymO2rPHCSCPgIVwwyqNYpgkqGWnn9Qg97Wc-zrTBiRJp0Dn4lcYvkbbeBrblZDOy6PnPFp33-WZ7lcaVeR6uNGqphQxCYv8pbti5Q9QYcc6IzYpvzsgDCbIVhuzQ9Px2-l6qVg6S-i-cYwQfxBYnVSyVmryuGSkIha2AezYJk2~0k7-byeJ0q57Re~aZy6boIDa2qtaOyi-RDbCWAoIIfOycwkAvqf5nG8KOVwGzvFEjYuExyP3f9ZlAAAA";
						int		boot_port 	= 52896;
						String	boot_nid	= "6d3dh2bwrafjdx4ba46zb6jvbnnt2g3r";
							
						NodeInfo boot_ninf = new NodeInfo( new NID( Base32.decode( boot_nid )), new Destination( boot_dest ), boot_port );
				
						if ( destroyed ){
							
							throw( new Exception( "Router destroyed" ));
						}
						
						I2PAppContext ctx = router.getContext();
												
						int		dht_port;
						NID		dht_nid;
						
						if ( use_existing_nid ){
							
							dht_port = Integer.parseInt( dht_port_str );
							dht_nid	= new NID( Base32.decode( dht_NID_str ));
			
						}else{
							
							dht_port = 10000 + RandomUtils.nextInt( 65533 - 10000 );	// NodeInfo treats >= 65535 as invalid and reply_port = (query_)port+1
							dht_nid = NodeInfo.generateNID(my_dest.calculateHash(), dht_port, ctx.random());
							
							dht_props.setProperty( "dest", my_dest.toBase64());
							dht_props.setProperty( "port", String.valueOf( dht_port ));
							dht_props.setProperty( "nid", Base32.encode( dht_nid.getData()));
							
							I2PHelperUtils.writeProperties( dht_config, dht_props );
						}
						
						NodeInfo my_node_info = new NodeInfo( dht_nid, my_dest, dht_port );
			
						log( "MyDest: " + full_dest );
						log( "        " + b32_dest  + ":" + dht_port + ", existing=" + use_existing_key[0] );
						log( "MyNID:  " + Base32.encode( dht_nid.getData()) + ", existing=" + use_existing_nid );
			
						dht = new DHTI2P( config_dir, dht_index, is_secondary, sm_holder, my_node_info, is_bootstrap_node?null:boot_ninf, adapter );						
					}
					
					initialized = true;
										
				}catch( Throwable e ){
					
					e.printStackTrace();
					
					closeStuff();
					
					throw( new Exception( "Initialisation failed", e ));	
				}
			}
		}finally{
			
			init_sem.releaseForever();
		}
		
		if ( initialized ){
			
			adapter.stateChanged( this, true );
		}
	}
	
	protected Destination
	lookupDestination(
		byte[]		hash )
	
		throws Exception
	{
			// just used for testing, leave blocking
		
		return( sm_holder.lookupDest( new Hash( hash ), 30*1000 ));
	}
	
	public String
	getB32Address()
	{
		return( b32_dest );
	}
		
		/**
		 * May return null
		 * @return
		 */
	
	public I2PSMHolder
	getSocksSocketManager()
	{
		return( sm_holder );
	}
	
		/**
		 * May return null
		 * @return
		 */
	public I2PHelperDHT
	getDHT()
	{
		return( dht );
	}
	
	public I2PHelperDHT
	getDHT(
		boolean	throw_if_null )
		
		throws Exception
	{
		I2PHelperDHT result = dht;
		
		if ( result == null && throw_if_null ){
			
			throw( new Exception( "DHT unavailable" ));
		}
		
		return( result );
	}
	
	public I2PHelperDHT
	getDHTBlocking()
	{
		init_sem.reserve();
		
		return( dht );
	}
	
	public Integer
	getIntegerOption(
		String		name )
	{
		Properties props = dht_socket_manager_properties;
		
		if ( props != null ){
			
			Object obj = props.getProperty( name, null );
			
			if ( obj instanceof String ){
				
				try{
					return( Integer.parseInt((String)obj));
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
		}
		
		return( null );
	}
	
	public boolean
	updateSocketManagerOptions(
		Properties	_props )
	{
		I2PSMHolder sh = sm_holder;
		
		if ( sh != null ){
			
			Properties props = new Properties();
			
			props.putAll( _props );
			
			I2PHelperUtils.normalizeProperties(props);			
			
			sh.updateOptions( props );
			
			dht_socket_manager_properties.putAll( props );
			
			log( "Updating options - " + props );
			
			return( true );
		}
		
		return( false );
	}
	
	private int		last_auto_quantity = -1;
	private long 	last_auto_quantity_time;
	
	public void
	updatePeerCount(
		int		peers,
		int		min_quantity )
	{
		if ( sm_holder == null ){
			
			return;
		}
			
		int	target;
		
		if ( peers <= 5 ){
			
			target = 2;
			
		}else if ( peers <= 10 ){
			
			target = 3;
			
		}else if ( peers <= 20 ){
			
			target = 4;
			
		}else if ( peers <= 50 ){
			
			target = 5;
			
		}else{
			
			target = 6;
		}
					
		target = Math.max( target, min_quantity );
			
		Integer current_in 	= getIntegerOption( "inbound.quantity" );
		Integer current_out = getIntegerOption( "outbound.quantity" );
		
		boolean	update_now		= false;
		boolean	update_pending 	= false;
		
		if ( 	current_in == null 	|| 
				current_out == null	|| 
				Math.min( current_in, current_out ) != target ){
			
			if ( 	last_auto_quantity == -1 ||
					target > last_auto_quantity ){
				
				update_now = true;
				
			}else if ( target < last_auto_quantity ){
				
				if ( SystemTime.getMonotonousTime() - last_auto_quantity_time > 5*60*1000 ){
						
					update_now = true;
					
				}else{
					
					update_pending = true;
				}
			}
		}
			
		//System.out.println( "Update peer count for " + dht_index + " - " + peers + ": " + current_in + "/" + current_out + " -> " + target + ", update=" + update_now + "/" + update_pending );

		if ( update_now ){
						
			String s_target = String.valueOf( target );
			
			Properties props = new Properties();
			
			props.put("inbound.quantity", s_target );
			props.put("outbound.quantity", s_target );
			
			if ( updateSocketManagerOptions( props )){
				
				last_auto_quantity 			= target;
			}
		}
		
		if ( !update_pending ){
			
			last_auto_quantity_time	= SystemTime.getMonotonousTime();
		}
	}
	
	private void
	closeStuff()
	{
		try{
			if ( dht != null ){
				
				dht.stop();
				
				dht = null;
			}
		}catch( Throwable f ){
		}
		
		try{
			if ( sm_holder != null ){
				
				sm_holder.destroy();
				
				sm_holder = null;
			}
		}catch( Throwable f ){
		}	
	}
	
	protected void
	destroy()
	{
		synchronized( this ){
			
			if ( destroyed ){
				
				return;
			}
			
			destroyed	= true;
			
			try{
				closeStuff();
				
			}finally{
				
				init_sem.releaseForever();
			}
		}
	}
}
