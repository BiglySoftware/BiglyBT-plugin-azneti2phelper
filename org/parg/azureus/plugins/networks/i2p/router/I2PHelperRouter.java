/*
 * Created on Mar 19, 2014
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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.util.*;

import net.i2p.CoreVersion;
import net.i2p.I2PAppContext;
import net.i2p.client.I2PClient;
import net.i2p.client.I2PClientFactory;
import net.i2p.client.I2PSession;
import net.i2p.client.naming.NamingService;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.Base32;
import net.i2p.data.Destination;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelManagerFacade;
import net.i2p.router.transport.FIFOBandwidthLimiter;

import com.biglybt.core.util.AENetworkClassifier;
import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.AEThread2;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.DisplayFormatters;
import com.biglybt.core.util.SystemTime;
import com.biglybt.pif.download.Download;
import org.parg.azureus.plugins.networks.i2p.I2PHelperAdapter;
import org.parg.azureus.plugins.networks.i2p.I2PHelperDHT;
import org.parg.azureus.plugins.networks.i2p.I2PHelperPlugin;

import com.biglybt.core.proxy.AEProxySelectorFactory;


public class 
I2PHelperRouter 
{
	private static final String 	i2p_internal_host 	= "127.0.0.1";

	public static final int SM_TYPE_ROUTER	= -1;
	public static final int SM_TYPE_MIX		= 0;
	public static final int SM_TYPE_PURE	= 1;
	public static final int SM_TYPE_OTHER	= 2;
	
	public static final String	PARAM_SEND_KBS				= "azi2phelper.rate.send.max";
	public static final int		PARAM_SEND_KBS_DEFAULT		= 50;
	
	public static final String	PARAM_RECV_KBS				= "azi2phelper.rate.recv.max";
	public static final int		PARAM_RECV_KBS_DEFAULT		= PARAM_SEND_KBS_DEFAULT;
	
	public static final String	PARAM_SHARE_PERCENT			= "azi2phelper.share.percent";
	public static final int		PARAM_SHARE_PERCENT_DEFAULT	= 80;
	
	public static final String		PARAM_AUTO_QUANTITY_ADJUST			= "azi2phelper.auto.quantity.adjust";
	public static final boolean		PARAM_AUTO_QUANTITY_ADJUST_DEFAULT	= true;
	
	public static final String		PARAM_FLOODFILL_CONTROL			= "azi2phelper.floodfill.control";
	public static final int			PARAM_FLOODFILL_CONTROL_DEFAULT	= 0;

	public static final int		PARAM_FLOODFILL_CONTROL_VUZE	= 0;
	public static final int		PARAM_FLOODFILL_CONTROL_I2P		= 1;
	public static final int		PARAM_FLOODFILL_CONTROL_ON		= 2;
	public static final int		PARAM_FLOODFILL_CONTROL_OFF		= 3;
		// mix defaults
	
	public static final String	PARAM_MIX_ENABLED					= "azi2phelper.mix.enabled";
	public static final boolean	PARAM_MIX_ENABLED_DEFAULT			= true;

	public static final String	PARAM_MIX_INBOUND_HOPS				= "azi2phelper.mix.inbound.hops";
	public static final int		PARAM_MIX_INBOUND_HOPS_DEFAULT		= 1;	// little point in making this larger as mixed connections are inherently de-anonymisable
	
	public static final String	PARAM_MIX_INBOUND_QUANTITY			= "azi2phelper.mix.inbound.quantity";
	public static final int		PARAM_MIX_INBOUND_QUANTITY_DEFAULT	= 2;
	
	public static final String	PARAM_MIX_OUTBOUND_HOPS				= "azi2phelper.mix.outbound.hops";
	public static final int		PARAM_MIX_OUTBOUND_HOPS_DEFAULT		= 1;	

	public static final String	PARAM_MIX_OUTBOUND_QUANTITY			= "azi2phelper.mix.outbound.quantity";
	public static final int		PARAM_MIX_OUTBOUND_QUANTITY_DEFAULT	= 2;

		// i2p only defaults
	
	public static final String	PARAM_PURE_ENABLED						= "azi2phelper.pure.enabled";
	public static final boolean	PARAM_PURE_ENABLED_DEFAULT				= true;

	public static final String	PARAM_PURE_INBOUND_HOPS					= "azi2phelper.i2p.inbound.hops";
	public static final int		PARAM_PURE_INBOUND_HOPS_DEFAULT			= 3;	
	
	public static final String	PARAM_PURE_INBOUND_QUANTITY				= "azi2phelper.i2p.inbound.quantity";
	public static final int		PARAM_PURE_INBOUND_QUANTITY_DEFAULT		= 2;
	
	public static final String	PARAM_PURE_OUTBOUND_HOPS				= "azi2phelper.i2p.outbound.hops";
	public static final int		PARAM_PURE_OUTBOUND_HOPS_DEFAULT		= 3;	

	public static final String	PARAM_PURE_OUTBOUND_QUANTITY			= "azi2phelper.i2p.outbound.quantity";
	public static final int		PARAM_PURE_OUTBOUND_QUANTITY_DEFAULT	= 2;
	
		// other defaults
	
	public static final String	PARAM_OTHER_ENABLED					= "azi2phelper.other.enabled";
	public static final boolean	PARAM_OTHER_ENABLED_DEFAULT			= true;

	public static final String	PARAM_OTHER_INBOUND_HOPS				= "azi2phelper.other.inbound.hops";
	public static final int		PARAM_OTHER_INBOUND_HOPS_DEFAULT		= 3;	
	
	public static final String	PARAM_OTHER_INBOUND_QUANTITY			= "azi2phelper.other.inbound.quantity";
	public static final int		PARAM_OTHER_INBOUND_QUANTITY_DEFAULT	= 2;
	
	public static final String	PARAM_OTHER_OUTBOUND_HOPS				= "azi2phelper.other.outbound.hops";
	public static final int		PARAM_OTHER_OUTBOUND_HOPS_DEFAULT		= 3;	

	public static final String	PARAM_OTHER_OUTBOUND_QUANTITY			= "azi2phelper.other.outbound.quantity";
	public static final int		PARAM_OTHER_OUTBOUND_QUANTITY_DEFAULT	= 2;
	
	public static final int		DHT_MIX			= 0;
	public static final int		DHT_NON_MIX		= 1;
	
	private static final String[]	DHT_NAMES = { "Mixed", "Pure" };
	
	private static final boolean	FULL_STATS = false;

	private final I2PHelperPlugin			plugin;
	private final File						config_dir;

	private final Map<String,Object>		router_properties;
	
	
	private final boolean					is_bootstrap_node;
	private final boolean					is_vuze_dht;
	private final boolean					force_new_address;
	private final I2PHelperAdapter			adapter;
	
	private boolean		is_external_router;
	private String		i2p_host;
	private int			i2p_port;
	
	private int			rate_multiplier 		= 1;
	private boolean		floodfill_capable		= false;
	private String 		floodfill_control_value	= "";

	private volatile Router 			router;
	
	private Properties						sm_properties = new Properties();	
	
	private final I2PHelperRouterDHT[]			dhts;
	
	private Map<String,ServerInstance>		servers = new HashMap<String, ServerInstance>();
	
	private AESemaphore			init_sem	= new AESemaphore( "I2PRouterInit" );
	
	private volatile boolean	destroyed;
	
	public
	I2PHelperRouter(
		I2PHelperPlugin			_plugin,
		File					_config_dir,
		Map<String,Object>		_properties,
		boolean					_is_bootstrap_node,
		boolean					_is_vuze_dht,
		boolean					_force_new_address,
		int						_dht_count,
		I2PHelperAdapter		_adapter )
	{
		plugin				= _plugin;
		config_dir			= _config_dir;
		router_properties	= _properties;
		is_bootstrap_node	= _is_bootstrap_node;		// could be props one day
		is_vuze_dht			= _is_vuze_dht;
		force_new_address	= _force_new_address;
		adapter				= _adapter;
		
		dhts = new I2PHelperRouterDHT[_dht_count ];

		for ( int i=0;i<dhts.length;i++){
		
			I2PHelperRouterDHT dht = new I2PHelperRouterDHT( this, config_dir, i, is_bootstrap_node, is_vuze_dht, force_new_address, adapter );
			
			if ( i == DHT_MIX ){
				
				dht.setEnabled( getBooleanParameter( PARAM_MIX_ENABLED ));
				
			}else if ( i == DHT_NON_MIX ){
				
				dht.setEnabled( getBooleanParameter( PARAM_PURE_ENABLED ));
			}
			
			dhts[i] = dht;
		}
	}
	
	public int
	getIntegerParameter(
		String		name )
	{
		int	def;
		
		if ( name == PARAM_SEND_KBS ){
			
			def = PARAM_SEND_KBS_DEFAULT;
			
		}else if ( name == PARAM_RECV_KBS ){
			
			def = PARAM_RECV_KBS_DEFAULT;
			
		}else if ( name == PARAM_SHARE_PERCENT ){
			
			def = PARAM_SHARE_PERCENT_DEFAULT;
			
		}else if ( name == PARAM_FLOODFILL_CONTROL ){
			
			def = PARAM_FLOODFILL_CONTROL_DEFAULT;
			
			// mix
			
		}else if ( name == PARAM_MIX_INBOUND_HOPS ){
			
			def = PARAM_MIX_INBOUND_HOPS_DEFAULT;
			
		}else if ( name == PARAM_MIX_INBOUND_QUANTITY ){
			
			def = PARAM_MIX_INBOUND_QUANTITY_DEFAULT;
			
		}else if ( name == PARAM_MIX_OUTBOUND_HOPS ){
			
			def = PARAM_MIX_OUTBOUND_HOPS_DEFAULT;
			
		}else if ( name == PARAM_MIX_OUTBOUND_QUANTITY ){
			
			def = PARAM_MIX_OUTBOUND_QUANTITY_DEFAULT;
			
			// i2p
			
		}else if ( name == PARAM_PURE_INBOUND_HOPS ){
			
			def = PARAM_PURE_INBOUND_HOPS_DEFAULT;
			
		}else if ( name == PARAM_PURE_INBOUND_QUANTITY ){
			
			def = PARAM_PURE_INBOUND_QUANTITY_DEFAULT;
			
		}else if ( name == PARAM_PURE_OUTBOUND_HOPS ){
			
			def = PARAM_PURE_OUTBOUND_HOPS_DEFAULT;
			
		}else if ( name == PARAM_PURE_OUTBOUND_QUANTITY ){
			
			def = PARAM_PURE_OUTBOUND_QUANTITY_DEFAULT;
			
			
			// other
		}else if ( name == PARAM_OTHER_INBOUND_HOPS ){
			
			def = PARAM_OTHER_INBOUND_HOPS_DEFAULT;
			
		}else if ( name == PARAM_OTHER_INBOUND_QUANTITY ){
			
			def = PARAM_OTHER_INBOUND_QUANTITY_DEFAULT;
			
		}else if ( name == PARAM_OTHER_OUTBOUND_HOPS ){
			
			def = PARAM_OTHER_OUTBOUND_HOPS_DEFAULT;
			
		}else if ( name == PARAM_OTHER_OUTBOUND_QUANTITY ){
			
			def = PARAM_OTHER_OUTBOUND_QUANTITY_DEFAULT;
					
		}else{
			
			Debug.out( "Unknown parameter: " + name );
			
			return( 0 );
		}
		
		Object val = router_properties.get( name );
		
		if ( val instanceof Number ){
			
			return(((Number)val).intValue());
		}else{
			
			return( def );
		}
	}
	
	private String
	getIntegerParameterAsString(
		String	name )
	{
		return( String.valueOf( getIntegerParameter( name )));
	}
	
	public boolean
	getBooleanParameter(
		String		name )
	{
		boolean	def;
		
		if ( name == PARAM_AUTO_QUANTITY_ADJUST ){
			
			def = PARAM_AUTO_QUANTITY_ADJUST_DEFAULT;
			
		}else if ( name == PARAM_MIX_ENABLED ){
			
			def = PARAM_MIX_ENABLED_DEFAULT;
			
		}else if ( name == PARAM_PURE_ENABLED ){
			
			def = PARAM_PURE_ENABLED_DEFAULT;
			
		}else if ( name == PARAM_OTHER_ENABLED ){
			
			def = PARAM_OTHER_ENABLED_DEFAULT;
					
		}else{
			
			Debug.out( "Unknown parameter: " + name );
			
			return( false );
		}
		
		Object val = router_properties.get( name );
		
		if ( val instanceof Boolean ){
			
			return(((Boolean)val).booleanValue());
			
		}else{
			
			return( def );
		}
	}
	
	private void
	addRateLimitProperties(
		Properties		props )
	{
		final long UNLIMITED = 100*1024;	// unlimited - 100MB/sec, have to use something
		
			// router bandwidth
			
		int mult 				= is_bootstrap_node?1:rate_multiplier;
		
		long	raw_base_in 	= is_bootstrap_node?500:getIntegerParameter(PARAM_RECV_KBS);
		
		long	base_in	= raw_base_in;
		
		if ( base_in <= 0 ){
			
			base_in = UNLIMITED;
			
		}else{
			
			base_in *= mult;
			
			base_in = Math.min( base_in, UNLIMITED );
		}
		
			// got to keep some bytes flowing here
		
		if ( base_in < 10 ){
			
			base_in = 10;
		}
		
		long burst_in_ks 	= base_in+(base_in/10);
		long burst_in_k		= burst_in_ks*20;
		
		long	raw_base_out 		= is_bootstrap_node?500:getIntegerParameter(PARAM_SEND_KBS);
		
		long	base_out	= raw_base_out;
		
		if ( base_out <= 0 ){
			
			base_out = UNLIMITED;	// unlimited - 100MB/sec
			
		}else{
			
			base_out *= mult;
			
			base_out = Math.min( base_out, UNLIMITED );
		}
		
			// got to keep some bytes flowing here
		
		if ( base_out < 10 ){
			
			base_out = 10;
		}
		
		long burst_out_ks 	= base_out+(base_out/10);
		long burst_out_k	= burst_out_ks*20;
		
		int share_pct	= is_bootstrap_node?75:getIntegerParameter(PARAM_SHARE_PERCENT);
		
		props.put( "i2np.bandwidth.inboundBurstKBytes", burst_in_k );
		props.put( "i2np.bandwidth.inboundBurstKBytesPerSecond", burst_in_ks );
		props.put( "i2np.bandwidth.inboundKBytesPerSecond", base_in );
		
		props.put( "i2np.bandwidth.outboundBurstKBytes", burst_out_k );
		props.put( "i2np.bandwidth.outboundBurstKBytesPerSecond", burst_out_ks );
		props.put( "i2np.bandwidth.outboundKBytesPerSecond", base_out );	
		
		props.put( "router.sharePercentage", share_pct );
				
		int floodfill_control = getIntegerParameter( PARAM_FLOODFILL_CONTROL );
		
		if ( floodfill_control == PARAM_FLOODFILL_CONTROL_VUZE ){
			
			if ( floodfill_capable ){
				
				floodfill_control_value = "auto";

			}else{
				
				if ( Math.min( raw_base_in, raw_base_out ) <= 100 ){	// kick in auto if > 100KB/sec and leave it up to I2P
					
					floodfill_control_value = "false";
					
				}else{
					
					floodfill_control_value = "auto";
				}
			}
		}else if ( floodfill_control == PARAM_FLOODFILL_CONTROL_I2P ){

			floodfill_control_value = "auto";
			
		}else if ( floodfill_control == PARAM_FLOODFILL_CONTROL_ON ){
			
			floodfill_control_value = "true";
			
		}else{
			
			floodfill_control_value = "false";
		}
		
		props.put( "router.floodfillParticipant", floodfill_control_value );
	}
	
	public void
	updateProperties()
	{		
		Properties props = new Properties();
		
		addRateLimitProperties( props );
		
		I2PHelperUtils.normalizeProperties( props );
		
		if ( router != null ){
			
			router.saveConfig( props, null );
			
			router.getContext().bandwidthLimiter().reinitialize();
		}
	}
	
	public void
	setRateMultiplier(
		int		mult )
	{
		rate_multiplier = mult;
	}
	
	public int
	getRateMultiplier()
	{
		return( rate_multiplier );
	}
	
	public void
	setFloodfillCapable(
		boolean		b )
	{
		floodfill_capable = b;
	}
	
	public boolean
	getFloodfillCapable()
	{
		return( floodfill_capable );
	}
	
	private void
	setupSMDefaultOpts(
		RouterContext		router_ctx )
		
		throws Exception
	{
		Properties opts = sm_properties;
				
		if ( router_ctx != null ){
		
			opts.putAll( router_ctx.getProperties());
		}
				
        // outbound speed limit
        // "i2cp.outboundBytesPerSecond"
        // tell router -> 
        //Properties newProps = new Properties();
        //newProps.putAll(_opts);
        // sess.updateOptions(newProps);
        
        
        // Dont do this for now, it is set in I2PSocketEepGet for announces,
        // we don't need fast handshake for peer connections.
        //if (opts.getProperty("i2p.streaming.connectDelay") == null)
        //    opts.setProperty("i2p.streaming.connectDelay", "500");
        if (opts.getProperty(I2PSocketOptions.PROP_CONNECT_TIMEOUT) == null)
            opts.setProperty(I2PSocketOptions.PROP_CONNECT_TIMEOUT, "75000");
        if (opts.getProperty("i2p.streaming.inactivityTimeout") == null)
            opts.setProperty("i2p.streaming.inactivityTimeout", "240000");
        if (opts.getProperty("i2p.streaming.inactivityAction") == null)
            opts.setProperty("i2p.streaming.inactivityAction", "1"); // 1 == disconnect, 2 == ping
        if (opts.getProperty("i2p.streaming.initialWindowSize") == null)
            opts.setProperty("i2p.streaming.initialWindowSize", "1");
        if (opts.getProperty("i2p.streaming.slowStartGrowthRateFactor") == null)
            opts.setProperty("i2p.streaming.slowStartGrowthRateFactor", "1");
        //if (opts.getProperty("i2p.streaming.writeTimeout") == null)
        //    opts.setProperty("i2p.streaming.writeTimeout", "90000");
        //if (opts.getProperty("i2p.streaming.readTimeout") == null)
        //    opts.setProperty("i2p.streaming.readTimeout", "120000");
        //if (opts.getProperty("i2p.streaming.maxConnsPerMinute") == null)
        //    opts.setProperty("i2p.streaming.maxConnsPerMinute", "2");
        //if (opts.getProperty("i2p.streaming.maxTotalConnsPerMinute") == null)
        //    opts.setProperty("i2p.streaming.maxTotalConnsPerMinute", "8");
        //if (opts.getProperty("i2p.streaming.maxConnsPerHour") == null)
        //    opts.setProperty("i2p.streaming.maxConnsPerHour", "20");
        if (opts.getProperty("i2p.streaming.enforceProtocol") == null)
            opts.setProperty("i2p.streaming.enforceProtocol", "true");
        //if (opts.getProperty("i2p.streaming.disableRejectLogging") == null)
        //    opts.setProperty("i2p.streaming.disableRejectLogging", "true");
        if (opts.getProperty("i2p.streaming.answerPings") == null)
            opts.setProperty("i2p.streaming.answerPings", "false");
        
        opts.setProperty( "i2p.streaming.disableRejectLogging", "false");
        opts.setProperty( "i2cp.dontPublishLeaseSet", "false" );

        if (opts.getProperty(I2PClient.PROP_SIGTYPE) == null)
        	opts.setProperty(I2PClient.PROP_SIGTYPE, "EdDSA_SHA512_Ed25519");
        
        setupSMExplicitOpts( opts, Constants.APP_NAME, SM_TYPE_ROUTER );
	}
	
	protected void
	setupSMExplicitOpts(
		Properties 	opts,
		String		nickname,
		int			sm_type )
		
		throws Exception
	{
		opts.setProperty( "outbound.nickname", nickname ); 

        opts.setProperty( "inbound.lengthVariance", "0" );
        opts.setProperty( "outbound.lengthVariance", "0" ); 

        if ( sm_type == SM_TYPE_ROUTER || sm_type == SM_TYPE_MIX ){
        	
        	if ( sm_type == SM_TYPE_MIX && !getBooleanParameter( PARAM_MIX_ENABLED )){
        		
        		throw( new Exception( "Mix destination is disabled" ));
        	}
        	
	        opts.setProperty( "inbound.length", getIntegerParameterAsString( PARAM_MIX_INBOUND_HOPS ));
	        opts.setProperty( "inbound.quantity", getIntegerParameterAsString( PARAM_MIX_INBOUND_QUANTITY )); 	
	        opts.setProperty( "outbound.length", getIntegerParameterAsString( PARAM_MIX_OUTBOUND_HOPS )); 
	        opts.setProperty( "outbound.quantity",  getIntegerParameterAsString( PARAM_MIX_OUTBOUND_QUANTITY ));
	        
        }else if ( sm_type == SM_TYPE_PURE ){
        	
        	if ( !getBooleanParameter( PARAM_PURE_ENABLED )){
        		
        		throw( new Exception( "Pure destination is disabled" ));
        	}
       	
	        opts.setProperty( "inbound.length", getIntegerParameterAsString( PARAM_PURE_INBOUND_HOPS ));	        
	        opts.setProperty( "inbound.quantity", getIntegerParameterAsString( PARAM_PURE_INBOUND_QUANTITY )); 	
	        opts.setProperty( "outbound.length", getIntegerParameterAsString( PARAM_PURE_OUTBOUND_HOPS )); 
	        opts.setProperty( "outbound.quantity",  getIntegerParameterAsString( PARAM_PURE_OUTBOUND_QUANTITY ));
	
        }else{
        	
        	if ( !getBooleanParameter( PARAM_OTHER_ENABLED )){
        		
        		throw( new Exception( "Other destination is disabled" ));
        	}
       	
	        opts.setProperty( "inbound.length", getIntegerParameterAsString( PARAM_OTHER_INBOUND_HOPS ));
	        opts.setProperty( "inbound.quantity", getIntegerParameterAsString( PARAM_OTHER_INBOUND_QUANTITY )); 	
	        opts.setProperty( "outbound.length", getIntegerParameterAsString( PARAM_OTHER_OUTBOUND_HOPS )); 
	        opts.setProperty( "outbound.quantity",  getIntegerParameterAsString( PARAM_OTHER_OUTBOUND_QUANTITY ));
        }
	}
	
	private void
	init(
		File		config_dir )
		
		throws Exception
	{
		if ( !config_dir.isDirectory()){
			
			config_dir.mkdirs();
			
			if ( !config_dir.isDirectory()){
				
				throw( new Exception( "Failed to create config dir '" + config_dir +"'" ));
			}
		}
				
			// setting this prevents stdout/stderr from being hijacked
		
		System.setProperty( "wrapper.version", "dummy" );	
	}
	
	public void
	initialiseRouter(
		int			i2p_internal_port,
		int			i2p_external_port )
	
		throws Exception
	{	
		try{
			is_external_router 	= false;
			i2p_host			= i2p_internal_host;
			i2p_port			= i2p_internal_port;

			init( config_dir );
			
			new File( config_dir, "router.ping" ).delete();
			
			File router_config = new File( config_dir, "router.config" );
			
			Properties router_props = I2PHelperUtils.readProperties( router_config );
			
				// router config
			
			router_props.put( "i2cp.port", i2p_internal_port );
			router_props.put( "i2np.upnp.enable", false );
			router_props.put( "i2p.streaming.answerPings", false );		// reverted this to false, 29/5/14 as things seem to be working
			
				// Note that TCP defaults to the same as UDP port
						
			router_props.put( "i2np.udp.port", i2p_external_port );
			router_props.put( "i2np.udp.internalPort", i2p_external_port );
	
			addRateLimitProperties( router_props );
			
				// router pools
					
			router_props.put( "router.inboundPool.backupQuantity", 0 );
			router_props.put( "router.inboundPool.length", 2 );
			router_props.put( "router.inboundPool.lengthVariance", 0 );
			router_props.put( "router.inboundPool.quantity", 2 );
			router_props.put( "router.outboundPool.backupQuantity", 0 );
			router_props.put( "router.outboundPool.length", 2 );
			router_props.put( "router.outboundPool.lengthVariance", 0 );
			router_props.put( "router.outboundPool.quantity", 2 );
					
			if ( is_bootstrap_node ){
			
				router_props.put( "router.floodfillParticipant", false );
			}
			
			I2PHelperUtils.normalizeProperties( router_props );
			
			I2PHelperUtils.writeProperties( router_config, router_props );
			
			router_props.setProperty( "router.configLocation", router_config.getAbsolutePath());
			router_props.setProperty( "i2p.dir.base" , config_dir.getAbsolutePath());
			router_props.setProperty( "i2p.dir.config" , config_dir.getAbsolutePath());

			if ( FULL_STATS ){
				
				Debug.out( "Turn off stats sometime!!!!" );
				
				router_props.put( "stat.full", "true" );
			}
			
			I2PAppContext ctx = I2PAppContext.getGlobalContext();

			ctx.logManager().setDefaultLimit( "ERROR" ); // "WARN" );

			router = new Router( router_props );
				
			router.setKillVMOnEnd( false );
			
			router.runRouter();
				
			// not needed since 0.9.13 as setting moved to Router constructor
			// router.setKillVMOnEnd( false );	// has to be done again after run phase as set true in that code :(

			RouterContext router_ctx = router.getContext();
			
			long start = SystemTime.getMonotonousTime();
			
			adapter.log( "Waiting for internal router startup on " + i2p_host + ":" + i2p_port );
						
			while( true ){
				
				if ( destroyed ){
					
					throw( new Exception( "Router has been shutdown" ));
				}
				
				try{
					Socket s = new Socket( Proxy.NO_PROXY );
					
					s.connect( new InetSocketAddress( i2p_internal_host, i2p_internal_port ), 1000 );
				
					s.close();
				
					break;
					
				}catch( Throwable e ){
					
					try{
						Thread.sleep(250);
						
					}catch( Throwable f ){
						
					}
				}
			}
				
			setupSMDefaultOpts( router_ctx );
            
			adapter.log( "Router startup complete: version=" + CoreVersion.getVersion() + ", elapsed=" + (SystemTime.getMonotonousTime() - start ));

			init_sem.releaseForever();
			
		}catch( Throwable e ){
			
			destroy();
			
			throw( new Exception( "Initialisation failed", e ));
		}
	}
	
	public void
	initialiseRouter(
		String		i2p_separate_host,
		int			i2p_separate_port )
	
		throws Exception
	{	
		try{
			is_external_router = true;
			
			i2p_host	= i2p_separate_host;
			i2p_port	= i2p_separate_port;
					
			AEProxySelectorFactory.getSelector().setProxy( new InetSocketAddress( i2p_host,i2p_port ), Proxy.NO_PROXY );
			
				// we need this so that the NameService picks up the hosts file in the plugin dir when using
				// an external router (dunno how/if to delegate lookups to the external router's hosts...)
						
			System.setProperty( "i2p.dir.config", config_dir.getAbsolutePath());
			
			init( config_dir );
		
			long start = SystemTime.getMonotonousTime();
			
			adapter.log( "Waiting for external router startup on " + i2p_host + ":" + i2p_port );
						
			while( true ){
				
				if ( destroyed ){
					
					throw( new Exception( "Router has been shutdown" ));
				}
				
				try{
					Socket s = new Socket( Proxy.NO_PROXY );
					
					s.connect( new InetSocketAddress( i2p_separate_host, i2p_separate_port ), 1000 );
				
					s.close();
				
					break;
					
				}catch( Throwable e ){
					
					try{
						Thread.sleep(1000);
						
					}catch( Throwable f ){
						
					}
				}
			}
			
			setupSMDefaultOpts( null );
			
			adapter.log( "Router startup complete, elapsed=" + (SystemTime.getMonotonousTime() - start ));
			       
			init_sem.releaseForever();
			
		}catch( Throwable e ){
			
			destroy();
			
			throw( new Exception( "Initialisation failed", e ));	
		}
	}
	
	protected void
	waitForInitialisation()
	
		throws Exception
	{
		init_sem.reserve();
		
		if ( destroyed ){
			
			throw( new Exception( "Router destroyed" ));
		}
	}
	
	public void
	initialiseDHTs()
	
		throws Exception
	{
			// second DHT is lazy initialised if/when selected
		
		if ( dhts[DHT_MIX].isEnabled()){
			
			dhts[DHT_MIX].initialiseDHT( i2p_host, i2p_port, DHT_NAMES[DHT_MIX], sm_properties );
		}
	}
	
	public I2PHelperRouterDHT
	selectDHT()
	{
		return(selectDHT((String[])null ));
	}
	
	public I2PHelperRouterDHT
	selectDHT(
		byte[]	torrent_hash )
	{
		if ( plugin != null ){
					
			try{
				Download download = plugin.getPluginInterface().getDownloadManager().getDownload( torrent_hash );
				
				return( selectDHT( download ));
			
			}catch( Exception e ){
			}
		}
		
		return( selectDHT());
	}
	
	public I2PHelperRouterDHT
	selectDHT(
		Download	download )
			
	{ 
		if ( download == null ){
		
			return( selectDHT());
			
		}else{
			
			return( selectDHT( plugin.getNetworks(download)));
		}
	}
	
	public I2PHelperRouterDHT
	selectDHT(
		Map<String,Object>		options )
	{
		String[] peer_networks = options==null?null:(String[])options.get( "peer_networks" );
		
		return( selectDHT( peer_networks ));
	}
	
	public I2PHelperRouterDHT
	selectDHT(
		String[]		peer_networks )
	{
		/*
		String str = "";
		
		if ( peer_networks != null ){
			
			for ( String net: peer_networks ){
			
				str += (str.length()==0?"":", ") + net;
			}
		}
		*/
		
		if ( dhts.length < 2 ){
			
			return( dhts[DHT_MIX] );
		}
		
		if ( peer_networks == null || peer_networks.length == 0 ){
			
			return( dhts[DHT_MIX] );
		}
		
		for ( String net: peer_networks ){
			
			if ( net == AENetworkClassifier.AT_PUBLIC ){
				
				return( dhts[DHT_MIX] );
			}
		}
				
		I2PHelperRouterDHT dht = dhts[DHT_NON_MIX];
			
		if ( !dht.isDHTInitialised()){
				
			try{
				dht.initialiseDHT( i2p_host, i2p_port, DHT_NAMES[DHT_NON_MIX], sm_properties );
					
			}catch( Throwable e ){
				
				Debug.out( e );
			}
		}
			
		return( dht );
	}
	
	public I2PHelperRouterDHT
	selectDHT(
		int		index )
	{
		if ( index >= dhts.length ){
			
			Debug.out( "Invalid DHT index" );
			
			return( null );
		}
		
		I2PHelperRouterDHT dht = dhts[index];
		
		if ( !dht.isDHTInitialised()){
			
			try{
				dht.initialiseDHT( i2p_host, i2p_port, DHT_NAMES[index], sm_properties );
				
			}catch( Throwable e ){
				
				Debug.out( e );
			}
		}
		
		return( dht );
	}
	
	public I2PHelperRouterDHT[]
	getAllDHTs()
	{
		return( dhts );
	}
	
	public Destination
	lookupDestination(
		byte[]		hash )
	
		throws Exception
	{
		return( dhts[DHT_MIX].lookupDestination( hash ));
	}
	
	public Router
	getRouter()
	{
		return( router );
	}
	
	public ServerInstance 
	createServer(
		final String			server_id,
		boolean					is_transient,
		int						sm_type,
		ServerAdapter			server_adapter )
		
		throws Exception
	{
		final ServerInstance server;
		
		synchronized( this ){
			
			if ( destroyed ){
				
				throw( new Exception( "Router destroyed" ));
			}
					
			ServerInstance existing_server = servers.get( server_id );
				
			if ( existing_server != null ){
				
				return( existing_server );
			}
			
			server = new ServerInstance( server_id, is_transient, sm_type, server_adapter );
			
			servers.put( server_id, server );
		}
		
		new AEThread2( "I2PServer:asyncinit" )
		{
			@Override
			public void
			run()
			{
				try{
					server.initialise();
					
				}catch( Throwable e ){
					
					Debug.out( e );
					
					synchronized( I2PHelperRouter.this ){
						
						servers.remove( server_id );
					}
				}
			}
		}.start();
		
		return( server );
	}
	
	public void
	destroy()
	{
		synchronized( this ){
			
			if ( destroyed ){
				
				return;
			}
			
			destroyed	= true;
			
			init_sem.releaseForever();
			
			try{
				if ( router != null ){
		
					router.shutdown( Router.EXIT_HARD );
					
					router = null;
				}
				
				for ( I2PHelperRouterDHT dht: dhts ){
				
					dht.destroy();
				}
			}catch( Throwable e ){
				
				e.printStackTrace();
			}
			
			for ( ServerInstance server: servers.values()){
				
				try{
					server.destroy();
					
				}catch( Throwable e ){
					
					e.printStackTrace();
				}
			}
			
			servers.clear();
		}
	}
	
	public void
	logInfo()
	{
		if ( destroyed ){
			
			return;
		}
		
		Router 			router 	= this.router;
		
		for ( int i=0;i<dhts.length;i++){
			
			I2PHelperDHT	dht		= dhts[i].getDHT();

			String str = "";
			
			if( dhts.length > 1 ){
				
				str = "DHT " + i;
			}
			
			String dht_str;
			
			if ( dht == null ){
				
				dht_str = "DHT is inactive";
				
			}else{
				
				dht_str = dht.getStats();
			}
			
			str += (str.length()==0?"":": ") + dht_str;
							
			adapter.log( str );
		}
		
		if ( router == null ){
			
			if ( !is_external_router ){
			
				adapter.log( "Router is inactive" );
			}
		}else{
			
			RouterContext router_ctx = router.getContext();
			
			adapter.log( "Known routers=" + router_ctx.netDb().getKnownRouters() + ", lease-sets=" + router_ctx.netDb().getKnownLeaseSets() + ", caps=" + router.getRouterInfo().getCapabilities() + ",ff=" + floodfill_control_value );
			
			TunnelManagerFacade tunnel_manager = router_ctx.tunnelManager();
			
			int	exploratory_tunnels		= tunnel_manager.getFreeTunnelCount() + tunnel_manager.getOutboundTunnelCount();
			int	client_tunnels			= tunnel_manager.getInboundClientTunnelCount()  + tunnel_manager.getOutboundClientTunnelCount();
			int participating_tunnels	= tunnel_manager.getParticipatingCount();
			
			adapter.log( "Tunnels: exploratory=" + exploratory_tunnels + ", client=" + client_tunnels + ", participating=" + participating_tunnels ); 
	
			adapter.log( "Throttle: msg_delay=" + router_ctx.throttle().getMessageDelay() + ", tunnel_lag=" + router_ctx.throttle().getTunnelLag() + ", tunnel_stat=" +  router_ctx.throttle().getTunnelStatus());
			
			FIFOBandwidthLimiter bwl = router_ctx.bandwidthLimiter();
			
			long recv_rate = (long)bwl.getReceiveBps();
			long send_rate = (long)bwl.getSendBps();
			
			//RateStat sendRate = router_ctx.statManager().getRate("bw.sendRate");
		    //RateStat recvRate = router_ctx.statManager().getRate("bw.recvRate"); 
			//System.out.println( "Rates: send=" + sendRate.getRate(60*1000).getAverageValue() + ", recv=" + recvRate.getRate(60*1000).getAverageValue());
		    
			if ( FULL_STATS ){
			
				adapter.log( "Lease repubs=" + router_ctx.statManager().getRate("netDb.republishLeaseSetCount" ).getLifetimeEventCount());
			}
			
			adapter.log( 
				"Rates: send=" + DisplayFormatters.formatByteCountToKiBEtcPerSec(send_rate) +
				", recv=" + DisplayFormatters.formatByteCountToKiBEtcPerSec(recv_rate) +
				", mult=" + rate_multiplier +
				"; Limits: send=" + DisplayFormatters.formatByteCountToKiBEtcPerSec(bwl.getOutboundKBytesPerSecond()*1024) + 
				", recv=" + DisplayFormatters.formatByteCountToKiBEtcPerSec(bwl.getInboundKBytesPerSecond()*1024));
		}
	}
	
	public String
	getStatusText()
	{	
		if ( destroyed ){
			
			return( "Destroyed" );
		}
		
		Router 			router 	= this.router;
		
		String dht_status = "";
		
		for ( int i=0;i<dhts.length;i++){
			
			I2PHelperDHT	dht		= dhts[i].getDHT();
			
			if ( dht != null ){
				
				dht_status += (dht_status.length()==0?"":"\n") + dht.getStatusString();
			}
		}
		
		if ( router == null  ){
			
			if ( is_external_router ){
				
				if ( dht_status.length() > 0 ){
					
					return( dht_status + "\nExternal Router" );
				}
			}
			
			return( adapter.getMessageText( "azi2phelper.status.initialising" ));
		}

		try{
				// can get various NPEs in this chunk when things are closing down so just whatever
			
			RouterContext router_ctx = router.getContext();
			
			FIFOBandwidthLimiter bwl = router_ctx.bandwidthLimiter();
			
			long recv_rate = (long)bwl.getReceiveBps();
			long send_rate = (long)bwl.getSendBps();
			
			long send_limit = bwl.getOutboundKBytesPerSecond()*1024;
			long recv_limit = bwl.getInboundKBytesPerSecond()*1024;
			
			String recv_limit_str = recv_limit > 50*1024*1024?"": (	"[" + DisplayFormatters.formatByteCountToKiBEtcPerSec(recv_limit) + "] " );
			String send_limit_str = send_limit > 50*1024*1024?"": (	"[" + DisplayFormatters.formatByteCountToKiBEtcPerSec(send_limit) + "] " );
			
			String router_str = 
				adapter.getMessageText( "azi2phelper.status.router",
						router_ctx.throttle().getTunnelStatus(),
						recv_limit_str + DisplayFormatters.formatByteCountToKiBEtcPerSec(recv_rate),
						send_limit_str + DisplayFormatters.formatByteCountToKiBEtcPerSec(send_rate));
	
			if ( dht_status.length() > 0 ){
				
				dht_status += "\n";
			}
			
			return( dht_status + router_str );
			
		}catch( Throwable e ){
			
			return( dht_status );
		}
	}
	
	public interface
	ServerAdapter
	{
		public void
		incomingConnection(
			ServerInstance	server,
			I2PSocket		socket )
			
			throws Exception;
	}
	
	public class
	ServerInstance
	{
		private final String			server_id;
		private final int				sm_type;
		private final ServerAdapter		server_adapter;
		
		private I2PSession 			session;
		private I2PSocketManager 	socket_manager;
		private I2PServerSocket		server_socket;

		private String				b32_dest;
		
		private volatile boolean	server_destroyed;
		
		private Map<String,Object>		user_properties = new HashMap<String, Object>();
		
		private
		ServerInstance(
			String				_server_id,
			boolean				_is_transient,
			int					_sm_type,
			ServerAdapter		_adapter )
			
			throws Exception
		{
			if ( !getBooleanParameter( PARAM_OTHER_ENABLED )){
			
				throw( new Exception( "Other destinations not enabled" ));
			}
			
			server_id			= _server_id;
			sm_type				= _sm_type;
			server_adapter		= _adapter;
			
			File dest_key_file 	= new File( config_dir,  server_id + "_dest_key.dat" );
	        
			Destination	dest = null;
			
			try{
				if ( _is_transient ){
					
					dest_key_file.delete();
				}
				
				if ( dest_key_file.exists()){
		    	   
		    		InputStream is = new FileInputStream( dest_key_file );
		    		
		    		try{
		    			dest = new Destination();
		    		
		    			dest.readBytes( is );
		    			
		    		}finally{
		    			
		    			is.close();
		    		}
				}
				
				if ( dest == null ){
					
					FileOutputStream	os = new FileOutputStream( dest_key_file );
					
					try{
						dest = I2PClientFactory.createClient().createDestination( os );
						
					}finally{
						
						os.close();
					}
				}
				
				b32_dest	= Base32.encode( dest.calculateHash().getData()) + ".b32.i2p";

				log( "address=" + b32_dest );
				
			}catch( Throwable e ){
				
				log( "Create failed: " + Debug.getNestedExceptionMessage( e ));
				
				throw( new Exception( "Failed to create server destination", e ));
			}
		}
		
		private void
		initialise()
		
			throws Exception
		{
			try{
				log( "Initializing..." );
					        
				File dest_key_file 	= new File( config_dir,  server_id + "_dest_key.dat" );
         	
		        I2PSocketManager	sm = null;

		        long start = SystemTime.getMonotonousTime();
		        
		        while( true ){
			
		        	InputStream is = new FileInputStream( dest_key_file );
		        	
			    	try{
			    		Properties sm_props = new Properties();
			    		
			    		sm_props.putAll( sm_properties );
			    		
			    		setupSMExplicitOpts( sm_props, Constants.APP_NAME + ": " + server_id, sm_type );
			    		
			    		sm = I2PSocketManagerFactory.createManager( is, i2p_host, i2p_port, sm_props );
			    	
			    	}finally{
			    		
			    		is.close();
			    	}
			    	
					if ( sm != null ){
						
						break;
					
					}else{
						
							// I've seen timeouts with 3 mins, crank it up
						
						if ( SystemTime.getMonotonousTime() - start > 15*60*1000 ){
							
							throw( new Exception( "Timeout creating socket manager" ));
						}
						
						Thread.sleep( 5000 );
									
						if ( server_destroyed ){
							
							throw( new Exception( "Server destroyed" ));
						}
					}
		        }
		        		
				log( "Waiting for socket manager startup" );
				
		        start = SystemTime.getMonotonousTime();

				while( true ){
					
					if ( server_destroyed ){
						
						sm.destroySocketManager();
						
						throw( new Exception( "Server destroyed" ));
					}
					
					session = sm.getSession();
					
					if ( session != null ){
						
						break;
					}
					
					if ( SystemTime.getMonotonousTime() - start > 3*60*1000 ){
						
						throw( new Exception( "Timeout waiting for socket manager startup" ));
					}

					
					Thread.sleep(250);
				}
								
				Destination my_dest = session.getMyDestination();
				
				b32_dest	= Base32.encode( my_dest.calculateHash().getData()) + ".b32.i2p";
				
				log( "Socket manager startup complete" );

				socket_manager	= sm;
						
				server_socket = socket_manager.getServerSocket();
				
				new AEThread2( "I2P:accepter" )
				{
					@Override
					public void
					run()
					{
						while( !server_destroyed ){
							
							try{
								I2PSocket socket = server_socket.accept();
								
								if ( socket == null ){
									
									if ( server_destroyed ){
										
										break;
										
									}else{
										
										Thread.sleep(500);
									}
								}else{
									try{
									
										server_adapter.incomingConnection( ServerInstance.this, socket );
									
									}catch( Throwable e ){
										
										Debug.out( e );
										
										try{
											socket.close();
											
										}catch( Throwable f ){
										}
									}
								}
							}catch( Throwable e ){
								
								if ( !Debug.getNestedExceptionMessage(e).toLowerCase(Locale.US).contains( "closed" )){

									Debug.out( e );
								}
								
								break;
							}
						}
					}
				}.start();
							

			}catch( Throwable e ){
				
				log( "Initialisation failed: " + Debug.getNestedExceptionMessage( e ));
				
				destroy();
				
				throw( new Exception( "Initialisation failed", e ));
				
			}finally{
				
				synchronized( this ){
					
					if ( server_destroyed ){
						
						destroy();
					}
				}
			}			
		}
		
		public I2PSession
		getSession()
		{
			return( session );
		}
		
		public I2PSocket
		connect(
			String					address,
			int						port,
			Map<String,Object>		options )
			
			throws Exception
		{
			if ( address.length() < 400 ){
				
				if ( !address.endsWith( ".i2p" )){
				
					address += ".i2p";
				}
			}
			
			Destination remote_dest;
			
			NamingService name_service = I2PAppContext.getGlobalContext().namingService();
			
			if ( name_service != null ){
			
				remote_dest = name_service.lookup( address );
				
			}else{
				
				remote_dest = new Destination();
	       
				try{
					remote_dest.fromBase64( address );
					
				}catch( Throwable e ){
					
					remote_dest = null;
				}
			}
			
			if ( remote_dest == null ){
				
				if ( address.endsWith( ".b32.i2p" )){
					
					remote_dest = socket_manager.getSession().lookupDest( address, 30*1000 );
				}
			}
			
			if ( remote_dest == null ){
				
				throw( new Exception( "Failed to resolve address '" + address + "'" ));
			}
						
			Properties overrides = new Properties();
			
			overrides.setProperty( "i2p.streaming.connectDelay", "250" );

            I2PSocketOptions socket_opts = socket_manager.buildOptions( overrides );
                        
            socket_opts.setPort( port );
            
            socket_opts.setConnectTimeout( 120*1000 );
            socket_opts.setReadTimeout( 120*1000 );
     
			I2PSocket socket = socket_manager.connect( remote_dest, socket_opts );
			
			return( socket );
		}
		
		public boolean
		isDestroyed()
		{
			return( server_destroyed );
		}
		
		public String
		getB32Dest()
		{
			return( b32_dest );
		}
		
		public void
		setUserProperty(
			String	key,
			Object	value )
		{	
			synchronized( user_properties ){
				
				user_properties.put( key, value );
			}
		}
			
		public Object
		getUserProperty(
			String	key )
		{	
			synchronized( user_properties ){
				
				return( user_properties.get( key ));
			}
		}
		
		private void
		destroy()
		{
			synchronized( this ){
				
				if ( server_destroyed ){
					
					return;
				}
				
				server_destroyed	= true;
				
				try{

					if ( socket_manager != null ){
						
						socket_manager.destroySocketManager();
						
						socket_manager = null;
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
			}
		}
		
		private void
		log(
			String	str )
		{
			adapter.log( server_id + ": " + str );
		}
	}
}
