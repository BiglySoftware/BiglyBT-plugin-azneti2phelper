/*
 * Created on Mar 17, 2014
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



package org.parg.azureus.plugins.networks.i2p;

import java.io.BufferedInputStream;
import java.io.File;


import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.data.Base32;
import net.i2p.data.Base64;
import net.i2p.data.Destination;
import net.i2p.router.Router;
import net.i2p.util.NativeBigInteger;

import com.biglybt.core.config.COConfigurationManager;
import com.biglybt.core.download.DownloadManager;
import com.biglybt.core.peer.PEPeer;
import com.biglybt.core.peer.PEPeerManager;
import com.biglybt.core.stats.transfer.LongTermStats;
import com.biglybt.core.stats.transfer.StatsFactory;
import com.biglybt.core.util.AENetworkClassifier;
import com.biglybt.core.util.AERunnable;
import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.AEThread2;
import com.biglybt.core.util.AsyncDispatcher;
import com.biglybt.core.util.BDecoder;
import com.biglybt.core.util.ByteFormatter;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.RandomUtils;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;
import com.biglybt.core.util.TimerEventPeriodic;
import com.biglybt.core.util.TorrentUtils;
import com.biglybt.core.util.UrlUtils;
import com.biglybt.pif.PluginAdapter;
import com.biglybt.pif.PluginConfig;
import com.biglybt.pif.PluginEvent;
import com.biglybt.pif.PluginEventListener;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.UnloadablePlugin;
import com.biglybt.pif.ddb.DistributedDatabase;
import com.biglybt.pif.ddb.DistributedDatabaseContact;
import com.biglybt.pif.ddb.DistributedDatabaseException;
import com.biglybt.pif.ddb.DistributedDatabaseKey;
import com.biglybt.pif.ddb.DistributedDatabaseProgressListener;
import com.biglybt.pif.ddb.DistributedDatabaseTransferHandler;
import com.biglybt.pif.ddb.DistributedDatabaseTransferType;
import com.biglybt.pif.ddb.DistributedDatabaseValue;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.ipc.IPCException;
import com.biglybt.pif.ipc.IPCInterface;
import com.biglybt.pif.logging.LoggerChannel;
import com.biglybt.pif.logging.LoggerChannelListener;
import com.biglybt.pif.torrent.Torrent;
import com.biglybt.pif.torrent.TorrentAttribute;
import com.biglybt.pif.ui.UIInstance;
import com.biglybt.pif.ui.UIManager;
import com.biglybt.pif.ui.UIManagerEvent;
import com.biglybt.pif.ui.UIManagerListener;
import com.biglybt.pif.ui.config.ActionParameter;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.ui.config.InfoParameter;
import com.biglybt.pif.ui.config.IntParameter;
import com.biglybt.pif.ui.config.Parameter;
import com.biglybt.pif.ui.config.ParameterGroup;
import com.biglybt.pif.ui.config.ParameterListener;
import com.biglybt.pif.ui.config.ParameterTabFolder;
import com.biglybt.pif.ui.config.StringParameter;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.biglybt.pif.ui.model.BasicPluginViewModel;
import com.biglybt.pif.utils.LocaleUtilities;
import com.biglybt.pifimpl.local.PluginCoreUtils;
import org.parg.azureus.plugins.networks.i2p.plugindht.I2PHelperDHTPluginInterface;
import org.parg.azureus.plugins.networks.i2p.router.I2PHelperRouter;
import org.parg.azureus.plugins.networks.i2p.router.I2PHelperRouterDHT;
import org.parg.azureus.plugins.networks.i2p.router.I2PHelperSocksProxy;
import org.parg.azureus.plugins.networks.i2p.router.I2PHelperRouter.ServerInstance;
import org.parg.azureus.plugins.networks.i2p.snarkdht.NodeInfo;
import org.parg.azureus.plugins.networks.i2p.swt.I2PHelperView;
import org.parg.azureus.plugins.networks.i2p.tracker.I2PDHTTrackerPlugin;
import org.parg.azureus.plugins.networks.i2p.tracker.I2PHelperTracker;
import org.parg.azureus.plugins.networks.i2p.util.I2PHelperHostnameService;
import org.parg.azureus.plugins.networks.i2p.vuzedht.DHTAZClient;
import org.parg.azureus.plugins.networks.i2p.vuzedht.DHTTransportContactI2P;
import org.parg.azureus.plugins.networks.i2p.vuzedht.I2PHelperAZDHT;
import org.parg.azureus.plugins.networks.i2p.vuzedht.I2PHelperAZDHT.DHTContact;
import org.parg.azureus.plugins.networks.i2p.vuzedht.I2PHelperAZDHT.DHTValue;
import org.parg.azureus.plugins.networks.i2p.vuzedht.I2PHelperDHTBridge;

import com.biglybt.core.CoreFactory;
import com.biglybt.core.dht.transport.DHTTransportAlternativeContact;
import com.biglybt.core.dht.transport.DHTTransportAlternativeNetwork;
import com.biglybt.core.dht.transport.udp.impl.DHTUDPUtils;
import com.biglybt.core.networkmanager.NetworkManager;
import com.biglybt.core.networkmanager.admin.NetworkAdmin;
import com.biglybt.core.proxy.AEProxyAddressMapper;
import com.biglybt.core.proxy.AEProxyFactory;
import com.biglybt.core.proxy.AEProxyFactory.PluginProxy;
import com.biglybt.core.tracker.TrackerPeerSource;
import com.biglybt.core.util.bloom.BloomFilter;
import com.biglybt.core.util.bloom.BloomFilterFactory;
import com.biglybt.plugin.dht.DHTPluginInterface;
import com.biglybt.plugin.dht.DHTPluginOperationListener;
import com.biglybt.plugin.dht.DHTPluginValue;
import com.biglybt.plugin.dht.DHTPluginContact;
import com.biglybt.plugin.upnp.UPnPMapping;
import com.biglybt.plugin.upnp.UPnPPlugin;
import com.biglybt.net.magneturi.MagnetURIHandler;
import com.biglybt.net.magneturi.MagnetURIHandlerException;
import com.biglybt.net.magneturi.MagnetURIHandlerListener;
import com.biglybt.net.magneturi.MagnetURIHandlerProgressListener;


public class 
I2PHelperPlugin 
	implements UnloadablePlugin, I2PHelperAdapter
{	
	/*
 		CHANGES REQUIRED
 		----------------
        
        
       	NativeBigInteger: Added load attempt from classes's loader (i.e. plugin class loader)
    		URL resource = ClassLoader.getSystemResource(resourceName);	// existing line in loadFromResource
    	    if (resource == null) {
        		// PARG added search via plugin class loader as well
        		resource = NativeBigInteger.class.getClassLoader().getResource(resourceName);
        	}

       	NativeBigInteger: added some temporary load limit support
        	
            public interface
		    ModPowLimiter
		    {
		    	public void
		    	handleCall(
		    		long		start,
		    		long		end );
		    }
		    
		    private static volatile ModPowLimiter	mod_pow_limiter = 
		    		new ModPowLimiter() {
						
						@Override
						public void handleCall(long start,long end) {
						}
					}; 
		    
			public static void
			setModPowLimiter(
				ModPowLimiter	l )
			{
				mod_pow_limiter = l;
			}
			
			// since 0.9.26 there are 2 methods that we rate-limit (not modInverse as cost is low and relatively infrequently us0ed)
			
		    @Override
		    public BigInteger modPow(BigInteger exponent, BigInteger m) {
		        // Where negative or zero values aren't legal in modPow() anyway, avoid native,
		        // as the Java code will throw an exception rather than silently fail or crash the JVM
		        // Negative values supported as of version 3
		    	BigInteger result;
		    	long start = System.nanoTime();
		        if (_nativeOk3 || (_nativeOk && signum() >= 0 && exponent.signum() >= 0 && m.signum() > 0))
		            result = new NativeBigInteger(nativeModPow(toByteArray(), exponent.toByteArray(), m.toByteArray()));
		        else
		            result = super.modPow(exponent, m);
		        
		        mod_pow_limiter.handleCall( start, System.nanoTime());
		        return( result );
		    }
		  
		    public BigInteger modPowCT(BigInteger exponent, BigInteger m) {
		    	BigInteger result;
		    	long start = System.nanoTime();
		        if (_nativeCTOk)
		            result = new NativeBigInteger(nativeModPowCT(toByteArray(), exponent.toByteArray(), m.toByteArray()));
		        else
		            result = modPow(exponent, m);
		        mod_pow_limiter.handleCall( start, System.nanoTime());
		        return( result );
		    }
		    
		
		Make message input stream sorta event driven
		------------------------------
		
		MessageInputStream - add stuff at bottom and replace all calls to .notifyAll with method call to notifyActivity
		
			Made MessageInputStream public
		
		    private ActivityListener	activity_listener;

		    public void
		    setActivityListener(
		    	ActivityListener		l )
		    {
		    	synchronized (_dataLock) {
		    		
		    		activity_listener	= l;
		    	}
		    }
		    		
		    public void 
		    notifyActivity() 
		    {
		    	synchronized (_dataLock) {
		    		
		    		_dataLock.notifyAll(); 
		    		
		    		if ( activity_listener != null ){
		    			
		    			try{
		    				activity_listener.activityOccurred();
		    				
		    			}catch( Throwable e ){
		    				
		    				e.printStackTrace();
		    			}
		    		}
		    	} 
		    }
		
		    
		    public interface
		    ActivityListener
		    {
		    	public void
		    	activityOccurred();
		    }
    
    
    		7 locations - _dataLock.notifyAll() -> notifyActivity()
    		+++++++++++
    		
    	Fix OOM
    	-------
    	ReusableGZIPOutputStream
    	
    	Changed method:
    	
	    	public static void release(ReusableGZIPOutputStream out) {
	        	out.reset();
	        	if (ENABLE_CACHING){
	            	_available.offer(out);
	        	}else{
	        		out.end();
	        	}
	    	}
		
		Added method:
			void end()
	    	{
	    		def.end();
	    	}
		
		
		Router Console app
		------------------
		Fix the loading 
		
		LoadClientAppsJob - replace system class - 2 places
                cl = LoadClientAppsJob.class.getClassLoader(); // PARG ClassLoader.getSystemClassLoader();
                _cl = LoadClientAppsJob.class.getClassLoader(); // PARG ClassLoader.getSystemClassLoader();
                
                
            
        NO LONGER NEEDED
        ----------------
                
                
	 	----Router: (patch integrated in 0.9.19, yay! commented out System.setProperties for timezone, http agent etc in static initialiser
	 
	 	----RoutingKeyGenerator: (patch integrated in 0.9.13, yay!) Fixed up SimpleDateFormat as it assumes GMT (TimeZone default used within SimpleDateFormat)
	     	private final static SimpleDateFormat _fmt = new SimpleDateFormat(FORMAT, Locale.UK);
    		static{
    			_fmt.setCalendar( _cal );	 // PARG
    		}
    		        	
        ----CoreVersion: (patch integrated in 0.9.19, yay!)Added a getVersion method to avoid constant getting cached within Vuze when printing it
            public static String
		    getVersion()
		    {
		    	return( VERSION );
		    }
		    
        ----UDPReceiver - hacked in a sleep(1000) after detection of exception on reading socket to avoid 100% CPU issue
        		// 0.9.16 has some core changes to hopefully prevent this, so no hack required!
        
        


	*/
	
	static{
		System.setProperty( "I2P_DISABLE_DNS_CACHE_OVERRIDE", "1" );
		System.setProperty( "I2P_DISABLE_HTTP_AGENT_OVERRIDE", "1" );
		System.setProperty( "I2P_DISABLE_HTTP_KEEPALIVE_OVERRIDE", "1" );
		System.setProperty( "I2P_DISABLE_TIMEZONE_OVERRIDE", "1" );
		System.setProperty( "I2P_DISABLE_OUTPUT_OVERRIDE", "1" );
	}
	
	private static final String	BOOTSTRAP_SERVER = "http://i2pboot.vuze.com:60000/?getNodes=true";
	
	private PluginInterface			plugin_interface;
	private TorrentAttribute		ta_networks;
	private PluginConfig			plugin_config;
	private LoggerChannel 			log;
	private BasicPluginConfigModel 	config_model;
	private BasicPluginViewModel	view_model;
	private LocaleUtilities			loc_utils;

	private int						dht_count	= 2;
	private String[]				dht_addresses 	= new String[ dht_count ];
	private String[]				dht_addresses2 	= new String[ dht_count ];
	
	private InfoParameter			i2p_address_param;
	private IntParameter 			int_port_param;
	private IntParameter 			ext_port_param;
	private IntParameter 			socks_port_param;
	private BooleanParameter 		socks_allow_public_param;
	private InfoParameter 			port_info_param;
	
	private int						active_int_port;
	private int						active_ext_port;
	
	private I2PHelperView			ui_view;
	
	private boolean					plugin_enabled;
	
	private boolean					dht_enabled;
	private boolean					dht_secondaries_enabled;
	
	private volatile I2PHelperRouter		router;
	private volatile I2PHelperTracker		tracker;
	
	private final AESemaphore		router_init_sem = new AESemaphore( "I2P:routerinit" );
	
	private final AsyncDispatcher	lookup_dispatcher = new AsyncDispatcher( "I2P:lookup" );
	
	
	private File			lock_file;
	private InputStream		lock_stream;
	
	private I2PHelperSocksProxy					socks_proxy;
	private Map<Proxy,ProxyMapEntry>			proxy_map 				= new IdentityHashMap<Proxy, ProxyMapEntry>();

	private I2PHelperMessageHandler		message_handler;
	
	private TimerEventPeriodic			timer_event;
	
	private I2PHelperAltNetHandler		alt_network_handler;
	
	private I2PHelperNetworkMixer		network_mixer;
	
	private MagnetURIHandlerListener	magnet_handler =
		new MagnetURIHandlerListener()
		{
			@Override
			public byte[]
			badge()
			{
				return( null );
			}
			
			@Override
			public byte[]
			download(
				MagnetURIHandlerProgressListener	progress,
				byte[]								hash,
				String								args,
				InetSocketAddress[]					sources,
				long								timeout )
			
				throws MagnetURIHandlerException
			{
				if ( args.contains( "maggot_sha1" )){
					
						// Snark blacklists nodes that attempt to get maggots and we currently can't tell Snark addresses
						// returned from get_peers from ones that don't blacklist :( So disable this for the moment
					
					return( null );

					//return( handleMaggotRequest( progress, hash, args, timeout ));
					
				}else{
					
					return( null );
				}
			}
			
			@Override
			public boolean
			download(
				URL			magnet_url )
			
				throws MagnetURIHandlerException
			{
				return( false );
			}
			
			@Override
			public boolean
			set(
				String	name,
				Map values )
			{
				return( false );
			}
			
			@Override
			public int
			get(
				String	name,
				Map 	values )
			{
				return( Integer.MIN_VALUE );
			}
		};
		
	private int DEST_HISTORY_MAX	= 100;
	
	private Map<String,Destination>		dest_map = 
		new LinkedHashMap<String,Destination>(DEST_HISTORY_MAX,0.75f,true)
		{
			@Override
			protected boolean
			removeEldestEntry(
		   		Map.Entry<String,Destination> eldest) 
			{
				return size() > DEST_HISTORY_MAX;
			}
		};
	
	private int LOCAL_PORT_HISTORY_MAX	= 512;
	
	private Map<Integer,Integer>		local_port_map = 
		new LinkedHashMap<Integer,Integer>(LOCAL_PORT_HISTORY_MAX,0.75f,true)
		{
			@Override
			protected boolean
			removeEldestEntry(
		   		Map.Entry<Integer,Integer> eldest) 
			{
				return size() > LOCAL_PORT_HISTORY_MAX;
			}
		};
			
	private static final int EXTERNAL_BOOTSTRAP_PERIOD = 30*60*1000;
		
	private long		last_external_bootstrap;
	private long		last_external_bootstrap_import;
	private List<Map>	last_external_bootstrap_nodes;
		
	private static final int NODES_FROM_PEERS_MAX	= 10;
	private LinkedList<NodeInfo>	bootstrap_nodes_from_peers = new LinkedList<NodeInfo>();
	
	private static DDBTestXFer	test_xfer = new DDBTestXFer();
	
	private I2PHelperDHTBridge	dht_bridge	= new I2PHelperDHTBridge( this );
	
	private I2PHelperHostnameService	hostname_service;
	
	private I2PHelperSocketForwarder	socket_forwarder = new I2PHelperSocketForwarder();
	
	private static final int CPU_THROTTLE_DEFAULT	= 2;
		
	private volatile boolean	unloaded;
	
	@Override
	public void
	initialize(
		PluginInterface		pi )
		
		throws PluginException
	{
		try{
			plugin_interface	= pi;
			
			ta_networks 	= plugin_interface.getTorrentManager().getAttribute( TorrentAttribute.TA_NETWORKS );

			setUnloadable( true );
			
			final File plugin_dir = pi.getPluginconfig().getPluginUserFile( "tmp.tmp" ).getParentFile();

			lock_file = new File( plugin_dir, ".azlock" );
			
			lock_file.delete();
			
			if ( lock_file.createNewFile()){

				lock_stream = new FileInputStream( lock_file );
				
			}else{
				
				throw( new PluginException( "Another instance of " + Constants.getAppName() + " appears to be running, can't create lock file '" + lock_file + "'"));
			}
			
			readPluginInfo();
			
			loc_utils = plugin_interface.getUtilities().getLocaleUtilities();
			
			log	= plugin_interface.getLogger().getTimeStampedChannel( "I2PHelper");


			// Need these set before NativeBigInteger, as it loads up some native
			// libraries into the current directory otherwise
			
			System.setProperty( "i2p.dir.config", plugin_dir.getAbsolutePath());
			System.setProperty( "i2p.dir.base", plugin_dir.getAbsolutePath());
			
			final UIManager	ui_manager = plugin_interface.getUIManager();

			view_model = ui_manager.createBasicPluginViewModel( loc_utils.getLocalisedMessageText( "azi2phelper.name" ));

			view_model.getActivity().setVisible( false );
			view_model.getProgress().setVisible( false );
			
			log.addListener(
					new LoggerChannelListener()
					{
						@Override
						public void
						messageLogged(
							int		type,
							String	content )
						{
							BasicPluginViewModel vm = view_model;
							
							if ( vm != null ){
							
								vm.getLogArea().appendText( content + "\n" );
							}
						}
						
						@Override
						public void
						messageLogged(
							String		str,
							Throwable	error )
						{
							BasicPluginViewModel vm = view_model;
							
							if ( vm != null ){
								
								vm.getLogArea().appendText( str + "\n" );
								vm.getLogArea().appendText( error.toString() + "\n" );
							}
						}
					});
				
			hostname_service = new I2PHelperHostnameService( this, plugin_dir );

			plugin_config = plugin_interface.getPluginconfig();
						
			config_model = ui_manager.createBasicPluginConfigModel( "plugins", "azi2phelper.name" );

			view_model.setConfigSectionID( "azi2phelper.name" );

			config_model.addLabelParameter2( "azi2phelper.info1" );
			config_model.addLabelParameter2( "azi2phelper.info2" );
			
			config_model.addHyperlinkParameter2( "azi2phelper.i2p.link", loc_utils.getLocalisedMessageText( "azi2phelper.i2p.link.url" ));
			config_model.addHyperlinkParameter2( "azi2phelper.plugin.link", loc_utils.getLocalisedMessageText( "azi2phelper.plugin.link.url" ));

			final BooleanParameter enable_param = config_model.addBooleanParameter2( "enable", "azi2phelper.enable", true );

			
				// Bandwidth
			
			final BooleanParameter link_rates_param = config_model.addBooleanParameter2( "azi2phelper.link.rates", "azi2phelper.link.rates", false );
			
			final IntParameter up_limit_param 			= config_model.addIntParameter2( I2PHelperRouter.PARAM_SEND_KBS, I2PHelperRouter.PARAM_SEND_KBS, I2PHelperRouter.PARAM_SEND_KBS_DEFAULT, 0, Integer.MAX_VALUE );
			final IntParameter down_limit_param 		= config_model.addIntParameter2( I2PHelperRouter.PARAM_RECV_KBS, I2PHelperRouter.PARAM_RECV_KBS, I2PHelperRouter.PARAM_RECV_KBS_DEFAULT, 0, Integer.MAX_VALUE );
			
			final IntParameter limit_multiplier_param 	= config_model.addIntParameter2( "azi2phelper.rate.multiplier", "azi2phelper.rate.multiplier", 2, 1, 100 );
			limit_multiplier_param.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );

			final IntParameter share_percent_param 		= config_model.addIntParameter2( I2PHelperRouter.PARAM_SHARE_PERCENT, I2PHelperRouter.PARAM_SHARE_PERCENT, I2PHelperRouter.PARAM_SHARE_PERCENT_DEFAULT, 10, 100 );
						
			ParameterListener link_listener = 
				new ParameterListener()
				{
					private TimerEventPeriodic event;
					
					@Override
					public void
					parameterChanged(
						Parameter 	param ) 
					{
						boolean	is_linked = link_rates_param.getValue();
						
						if ( is_linked ){
							
							if ( event == null ){
								
								syncRates();
								
								event = SimpleTimer.addPeriodicEvent(
									"I2PRateLinker",
									15*1000,
									new TimerEventPerformer()
									{
										@Override
										public void
										perform(
											TimerEvent event) 
										{
											if ( !link_rates_param.getValue()){
												
												return;
											}
											
											syncRates();
										}
									});
							}
						}else{
							
							if ( event != null ){
								
								event.cancel();
								
								event = null;
							}
						}
					}
					
					private void
					syncRates()
					{
						I2PHelperRouter current_router = router;
						
						if ( current_router != null ){
							
							if ( current_router.getRateMultiplier() != 1 ){
								
								current_router.setRateMultiplier( 1 );
								
								current_router.updateProperties();
							}
						}
						
						int dl_limit = NetworkManager.getMaxDownloadRateBPS() / 1024;
						
						int ul_limit;
						
						if (NetworkManager.isSeedingOnlyUploadRate()){
							
							ul_limit= NetworkManager.getMaxUploadRateBPSSeedingOnly() / 1024;
							
						}else{
							
							ul_limit = NetworkManager.getMaxUploadRateBPSNormal() / 1024;
						}
						
						if ( up_limit_param.getValue() != ul_limit ){
							
							up_limit_param.setValue( ul_limit );
						}
						
						if ( down_limit_param.getValue() != dl_limit ){
							
							down_limit_param.setValue( dl_limit );
						}
					}
				};
			
			link_listener.parameterChanged( null );
				
			link_rates_param.addListener( link_listener );
			
			final BooleanParameter treat_as_lan_param = config_model.addBooleanParameter2( "azi2phelper.rates.use.lan", "azi2phelper.rates.use.lan", false );

			
			config_model.createGroup( 
					"azi2phelper.bandwidth.group",
					new Parameter[]{ 
						link_rates_param, up_limit_param, down_limit_param, limit_multiplier_param, share_percent_param, treat_as_lan_param	
					});
			
				// Network Mixing
			
			final BooleanParameter net_mix_enable		= config_model.addBooleanParameter2( "azi2phelper.netmix.enable", "azi2phelper.netmix.enable", true );
			final BooleanParameter seed_request_enable	= config_model.addBooleanParameter2( "azi2phelper.seedreq.enable", "azi2phelper.seedreq.enable", true );

			final IntParameter net_mix_incomp_num 		= config_model.addIntParameter2( "azi2phelper.netmix.incomp.num", "azi2phelper.netmix.incomp.num", 5 );
			
			final IntParameter net_mix_comp_num 		= config_model.addIntParameter2( "azi2phelper.netmix.comp.num", "azi2phelper.netmix.comp.num", 5 );
			
			config_model.createGroup( 
					"azi2phelper.netmix.group",
					new Parameter[]{ 
							net_mix_enable, seed_request_enable, net_mix_incomp_num, net_mix_comp_num	
					});
			
			
			// UI
			
			final BooleanParameter icon_enable		= config_model.addBooleanParameter2( "azi2phelper.ui.icon.enable", "azi2phelper.ui.icon.enable", true );

			config_model.createGroup( 
					"azi2phelper.ui.group",
					new Parameter[]{ 
							icon_enable	
					});
			// DHT
			
			final BooleanParameter dht_enable_param		= config_model.addBooleanParameter2( "azi2phelper.dht.enable", "azi2phelper.dht.enable", true );
			
			final BooleanParameter dht_sec_enable_param		= config_model.addBooleanParameter2( "azi2phelper.dht.sec.enable", "azi2phelper.dht.sec.enable", true );

			dht_enable_param.addEnabledOnSelection( dht_sec_enable_param );
			
			config_model.createGroup( 
					"azi2phelper.dht.group",
					new Parameter[]{ 
							dht_enable_param, dht_sec_enable_param	
					});
			
				// I2P Internals
			
			i2p_address_param 	= config_model.addInfoParameter2( "azi2phelper.i2p.address", getMessageText( "azi2phelper.i2p.address.pending" ));
			
			i2p_address_param.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );
			
			final ActionParameter new_id = config_model.addActionParameter2( "azi2phelper.new.identity", "azi2phelper.new.identity.button" );
			
			new_id.addListener(
				new ParameterListener() {
					
					@Override
					public void 
					parameterChanged(
						Parameter param) 
					{
						COConfigurationManager.setParameter( "azi2phelper.new.identity.required", true );
						
						COConfigurationManager.setParameter( "azi2phelper.change.id.time", SystemTime.getCurrentTime());
						
						COConfigurationManager.save();
						
						ui_manager.showMessageBox(
								"azi2phelper.restart.title",
								"azi2phelper.restart.message",
								UIManagerEvent.MT_OK );
					}
				});
				
			final IntParameter 	change_id	= config_model.addIntParameter2( "azi2phelper.change.id", "azi2phelper.change.id", 7, -1, 1024 );

			long	now = SystemTime.getCurrentTime();
			
			if ( !COConfigurationManager.getBooleanParameter( "azi2phelper.new.identity.required" )){
			
				int change_id_days = change_id.getValue();
			
				boolean change_it = false;
				
				if ( change_id_days < 0 ){
					
				}else if ( change_id_days == 0 ){
					
					change_it = true;
					
				}else{
					
					long last_change = COConfigurationManager.getLongParameter( "azi2phelper.change.id.time", 0 );
					
					if ( last_change == 0 ){
						
						COConfigurationManager.setParameter( "azi2phelper.change.id.time", now );
						
					}else{
						
						long	elapsed_days = ( now - last_change )/(24*60*60*1000);
						
						if ( elapsed_days >= change_id_days ){
							
							change_it = true;
						}
					}
				}
				
				if ( change_it ){
					
					COConfigurationManager.setParameter( "azi2phelper.change.id.time", now );

					COConfigurationManager.setParameter( "azi2phelper.new.identity.required", true );
				}
			}
						
			int_port_param 		= config_model.addIntParameter2( "azi2phelper.internal.port", "azi2phelper.internal.port", 0 );
			
			int_port_param.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );

			ext_port_param	 	= config_model.addIntParameter2( "azi2phelper.external.port", "azi2phelper.external.port", 0 );
			
			ext_port_param.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );
					
			final BooleanParameter mix_enabled_param 	= config_model.addBooleanParameter2( I2PHelperRouter.PARAM_MIX_ENABLED, "azi2phelper.enable", I2PHelperRouter.PARAM_MIX_ENABLED_DEFAULT );
			final IntParameter mix_in_hops_param 		= config_model.addIntParameter2( I2PHelperRouter.PARAM_MIX_INBOUND_HOPS, "azi2phelper.inbound.hops", I2PHelperRouter.PARAM_MIX_INBOUND_HOPS_DEFAULT, 0, I2PHelperRouter.MAX_HOPS );
			final IntParameter mix_in_quant_param 		= config_model.addIntParameter2( I2PHelperRouter.PARAM_MIX_INBOUND_QUANTITY, "azi2phelper.inbound.quantity", I2PHelperRouter.PARAM_MIX_INBOUND_QUANTITY_DEFAULT, 1, I2PHelperRouter.MAX_TUNNELS );
			final IntParameter mix_out_hops_param 		= config_model.addIntParameter2( I2PHelperRouter.PARAM_MIX_OUTBOUND_HOPS, "azi2phelper.outbound.hops", I2PHelperRouter.PARAM_MIX_OUTBOUND_HOPS_DEFAULT, 0, I2PHelperRouter.MAX_HOPS );
			final IntParameter mix_out_quant_param 		= config_model.addIntParameter2( I2PHelperRouter.PARAM_MIX_OUTBOUND_QUANTITY, "azi2phelper.outbound.quantity", I2PHelperRouter.PARAM_MIX_OUTBOUND_QUANTITY_DEFAULT, 1, I2PHelperRouter.MAX_TUNNELS );

			final BooleanParameter i2p_enabled_param 	= config_model.addBooleanParameter2( I2PHelperRouter.PARAM_PURE_ENABLED, "azi2phelper.enable", I2PHelperRouter.PARAM_PURE_ENABLED_DEFAULT );
			final IntParameter i2p_in_hops_param 		= config_model.addIntParameter2( I2PHelperRouter.PARAM_PURE_INBOUND_HOPS, "azi2phelper.inbound.hops", I2PHelperRouter.PARAM_PURE_INBOUND_HOPS_DEFAULT, 0, I2PHelperRouter.MAX_HOPS );
			final IntParameter i2p_in_quant_param 		= config_model.addIntParameter2( I2PHelperRouter.PARAM_PURE_INBOUND_QUANTITY, "azi2phelper.inbound.quantity", I2PHelperRouter.PARAM_PURE_INBOUND_QUANTITY_DEFAULT, 1, I2PHelperRouter.MAX_TUNNELS );
			final IntParameter i2p_out_hops_param 		= config_model.addIntParameter2( I2PHelperRouter.PARAM_PURE_OUTBOUND_HOPS, "azi2phelper.outbound.hops", I2PHelperRouter.PARAM_PURE_OUTBOUND_HOPS_DEFAULT, 0, I2PHelperRouter.MAX_HOPS );
			final IntParameter i2p_out_quant_param 		= config_model.addIntParameter2( I2PHelperRouter.PARAM_PURE_OUTBOUND_QUANTITY, "azi2phelper.outbound.quantity", I2PHelperRouter.PARAM_PURE_OUTBOUND_QUANTITY_DEFAULT, 1, I2PHelperRouter.MAX_TUNNELS );

			final BooleanParameter other_enabled_param 	= config_model.addBooleanParameter2( I2PHelperRouter.PARAM_OTHER_ENABLED, "azi2phelper.enable", I2PHelperRouter.PARAM_OTHER_ENABLED_DEFAULT );
			final IntParameter other_in_hops_param 		= config_model.addIntParameter2( I2PHelperRouter.PARAM_OTHER_INBOUND_HOPS, "azi2phelper.inbound.hops", I2PHelperRouter.PARAM_OTHER_INBOUND_HOPS_DEFAULT, 0, I2PHelperRouter.MAX_HOPS );
			final IntParameter other_in_quant_param 	= config_model.addIntParameter2( I2PHelperRouter.PARAM_OTHER_INBOUND_QUANTITY, "azi2phelper.inbound.quantity", I2PHelperRouter.PARAM_OTHER_INBOUND_QUANTITY_DEFAULT, 1, I2PHelperRouter.MAX_TUNNELS );
			final IntParameter other_out_hops_param 	= config_model.addIntParameter2( I2PHelperRouter.PARAM_OTHER_OUTBOUND_HOPS, "azi2phelper.outbound.hops", I2PHelperRouter.PARAM_OTHER_OUTBOUND_HOPS_DEFAULT, 0, I2PHelperRouter.MAX_HOPS );
			final IntParameter other_out_quant_param 	= config_model.addIntParameter2( I2PHelperRouter.PARAM_OTHER_OUTBOUND_QUANTITY, "azi2phelper.outbound.quantity", I2PHelperRouter.PARAM_OTHER_OUTBOUND_QUANTITY_DEFAULT, 1, I2PHelperRouter.MAX_TUNNELS );

			
			final Parameter[] tunnel_params =
				{ 	mix_enabled_param, mix_in_hops_param, mix_in_quant_param, mix_out_hops_param, mix_out_quant_param,
					i2p_enabled_param, i2p_in_hops_param, i2p_in_quant_param, i2p_out_hops_param, i2p_out_quant_param,
					other_enabled_param, other_in_hops_param, other_in_quant_param, other_out_hops_param, other_out_quant_param
				};
			
			for ( Parameter p: tunnel_params ){
				
				p.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );
			}
			
			socks_port_param 	= config_model.addIntParameter2( "azi2phelper.socks.port", "azi2phelper.socks.port", 0 );
			socks_port_param.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );

			socks_allow_public_param = config_model.addBooleanParameter2( "azi2phelper.socks.allow.public", "azi2phelper.socks.allow.public", false );
			socks_allow_public_param.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );

			int	int_port = int_port_param.getValue();
			
			boolean port_changed = false;
			
			if ( int_port == 0 ){
				
				int_port = plugin_config.getPluginIntParameter( "azi2phelper.internal.port.auto", 0 );
				
				if ( int_port == 0 || !testPort( int_port )){
					
					int_port = allocatePort( 17654, 0 );
					
					plugin_config.setPluginParameter( "azi2phelper.internal.port.auto", int_port );
					
					port_changed = true;
				}
			}else{
				if  ( !testPort( int_port )){
					
					log( "Testing of explicitly configured internal port " + int_port + " failed - this isn't good" );
				}
			}
			
			active_int_port	= int_port;
		
			int	ext_port = ext_port_param.getValue();
			
			if ( ext_port == 0 ){
				
				ext_port = plugin_config.getPluginIntParameter( "azi2phelper.external.port.auto", 0 );
				
				if ( ext_port == 0 || !testPort( ext_port )){
					
					ext_port = allocatePort( 23154, int_port );
					
					plugin_config.setPluginParameter( "azi2phelper.external.port.auto", ext_port );
					
					port_changed = true;
				}
			}else{
				
				if  ( !testPort( ext_port )){
					
					log( "Testing of explicitly configured external port " + ext_port + " failed - this isn't good" );
				}
			}

			active_ext_port	= ext_port;
			
			int	sock_port = socks_port_param.getValue();
			
			if ( sock_port != 0 ){
				
				if  ( !testPort( sock_port )){
					
					log( "Testing of explicitly configured SOCKS port " + sock_port + " failed - this isn't good" );
				}
			}
			
			port_info_param = config_model.addInfoParameter2( "azi2phelper.port.info", "" );
			port_info_param.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );

			updatePortInfo();
			
			final BooleanParameter use_upnp = config_model.addBooleanParameter2( "azi2phelper.upnp.enable", "azi2phelper.upnp.enable", true );
			
			final BooleanParameter always_socks = config_model.addBooleanParameter2( "azi2phelper.socks.always", "azi2phelper.socks.always", false );
		
			final IntParameter 	cpu_throttle	= config_model.addIntParameter2( "azi2phelper.cpu.throttle", "azi2phelper.cpu.throttle", CPU_THROTTLE_DEFAULT, 0, 100 );
			cpu_throttle.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );

			cpu_throttle.addListener(
					new ParameterListener() 
					{
						@Override
						public void
						parameterChanged(
							Parameter param ) 
						{
							cpu_throttle_factor = cpu_throttle.getValue();
						}
					});
			
			cpu_throttle_factor = cpu_throttle.getValue();
			
			final IntParameter 		floodfill_param 		= config_model.addIntParameter2( "azi2phelper.floodfill.control", "azi2phelper.floodfill.control", I2PHelperRouter.PARAM_FLOODFILL_CONTROL_VUZE, 0, 4 );
			floodfill_param.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );

			final BooleanParameter ext_i2p_param 		= config_model.addBooleanParameter2( "azi2phelper.use.ext", "azi2phelper.use.ext", false );
			ext_i2p_param.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );

			final StringParameter 	ext_i2p_host_param 		= config_model.addStringParameter2( "azi2phelper.use.ext.host", "azi2phelper.use.ext.host", "127.0.0.1" );
			ext_i2p_host_param.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );

			final IntParameter 		ext_i2p_port_param 		= config_model.addIntParameter2( "azi2phelper.use.ext.port", "azi2phelper.use.ext.port", 7654 );
			ext_i2p_port_param.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );

				// tunnel config
			
			final BooleanParameter tunnel_auto_quantities_param = config_model.addBooleanParameter2( "azi2phelper.tunnel.quant.auto", "azi2phelper.tunnel.quant.auto", true );
			tunnel_auto_quantities_param.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );

				// tunnel tab
			
			ParameterTabFolder	tunnel_tab = config_model.createTabFolder();
			
			ParameterGroup tunnel_tab1 = 
				config_model.createGroup( 
					"azi2phelper.internals.tunnels.mixed",
					new Parameter[]{ 
						mix_enabled_param, mix_in_hops_param, mix_in_quant_param, mix_out_hops_param, mix_out_quant_param	
					});

			mix_enabled_param.addEnabledOnSelection(mix_in_hops_param);
			mix_enabled_param.addEnabledOnSelection(mix_in_quant_param);
			mix_enabled_param.addEnabledOnSelection(mix_out_hops_param);
			mix_enabled_param.addEnabledOnSelection(mix_out_quant_param);
			
			ParameterGroup tunnel_tab2 = 
					config_model.createGroup( 
						"azi2phelper.internals.tunnels.i2ponly",
						new Parameter[]{ 
							i2p_enabled_param, i2p_in_hops_param, i2p_in_quant_param, i2p_out_hops_param, i2p_out_quant_param	
						});
			
			i2p_enabled_param.addEnabledOnSelection(i2p_in_hops_param);
			i2p_enabled_param.addEnabledOnSelection(i2p_in_quant_param);
			i2p_enabled_param.addEnabledOnSelection(i2p_out_hops_param);
			i2p_enabled_param.addEnabledOnSelection(i2p_out_quant_param);

			ParameterGroup tunnel_tab3 = 
					config_model.createGroup( 
						"azi2phelper.internals.tunnels.other",
						new Parameter[]{ 
							other_enabled_param, other_in_hops_param, other_in_quant_param, other_out_hops_param, other_out_quant_param	
						});
			
			other_enabled_param.addEnabledOnSelection(other_in_hops_param);
			other_enabled_param.addEnabledOnSelection(other_in_quant_param);
			other_enabled_param.addEnabledOnSelection(other_out_hops_param);
			other_enabled_param.addEnabledOnSelection(other_out_quant_param);

			
			tunnel_tab.addTab( tunnel_tab1 );
			tunnel_tab.addTab( tunnel_tab2 );
			tunnel_tab.addTab( tunnel_tab3 );

			ParameterGroup tunnel_group = config_model.createGroup( "azi2phelper.tunnel.info", new Parameter[]{ tunnel_auto_quantities_param, tunnel_tab });
			
			tunnel_group.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );
			
			config_model.createGroup( 
				"azi2phelper.internals.group",
				new Parameter[]{ 
						i2p_address_param, new_id, change_id, int_port_param, ext_port_param, floodfill_param,
						tunnel_group,
						socks_port_param, socks_allow_public_param,
						port_info_param, use_upnp, always_socks, cpu_throttle, ext_i2p_param, ext_i2p_host_param, ext_i2p_port_param });
			
			
			final StringParameter 	command_text_param = config_model.addStringParameter2( "azi2phelper.cmd.text", "azi2phelper.cmd.text", "" );
			command_text_param.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );

			final ActionParameter	command_exec_param = config_model.addActionParameter2( "azi2phelper.cmd.act1", "azi2phelper.cmd.act2" );
			command_exec_param.setMinimumRequiredUserMode( Parameter.MODE_ADVANCED );

			
			command_exec_param.addListener(
				new ParameterListener() 
				{
					@Override
					public void
					parameterChanged(
						Parameter param ) 
					{
						new AEThread2( "cmdrunner" )
						{
							@Override
							public void
							run()
							{
								try{
									command_exec_param.setEnabled( false );

									executeCommand( 
										command_text_param.getValue(),
										router,
										tracker,
										I2PHelperPlugin.this,
										I2PHelperPlugin.this );
									
								}catch( Throwable e){
									
									log( "Command failed: " + Debug.getNestedExceptionMessage( e ));
									
								}finally{
									
									command_exec_param.setEnabled( true );
								}
							}
						}.start();
					}
				});
			
			if ( port_changed ){
				
				plugin_config.save();
			}
			
			final int f_int_port = int_port;
			final int f_ext_port = ext_port;
			
			ParameterListener enabler_listener =
					new ParameterListener()
					{
						@Override
						public void
						parameterChanged(
							Parameter param )
						{
							plugin_enabled 			= enable_param.getValue();

							dht_enabled				= dht_enable_param.getValue();
							
							dht_secondaries_enabled	= dht_enabled && dht_sec_enable_param.getValue();
							
							boolean use_ext_i2p  	= ext_i2p_param.getValue();
							
							boolean	enabled_not_ext = plugin_enabled && !use_ext_i2p;
							
							boolean	is_linked = link_rates_param.getValue();

							link_rates_param.setEnabled( enabled_not_ext );
							up_limit_param.setEnabled( enabled_not_ext && !is_linked );
							down_limit_param.setEnabled( enabled_not_ext  && !is_linked );
							limit_multiplier_param.setEnabled( enabled_not_ext  && !is_linked );
							share_percent_param.setEnabled( enabled_not_ext );
							
							net_mix_enable.setEnabled( plugin_enabled );
							seed_request_enable.setEnabled( plugin_enabled );
							net_mix_incomp_num.setEnabled( plugin_enabled );
							net_mix_comp_num.setEnabled( plugin_enabled );
							
							icon_enable.setEnabled( plugin_enabled );
							
							i2p_address_param.setEnabled( plugin_enabled );
							new_id.setEnabled( plugin_enabled );
							change_id.setEnabled( plugin_enabled );
							int_port_param.setEnabled( enabled_not_ext );
							ext_port_param.setEnabled( enabled_not_ext);
							
							tunnel_auto_quantities_param.setEnabled( plugin_enabled );
							for ( Parameter p: tunnel_params ){
								p.setEnabled( plugin_enabled );
							}
							
							socks_port_param.setEnabled( plugin_enabled );
							socks_allow_public_param.setEnabled( plugin_enabled );
							port_info_param.setEnabled( plugin_enabled );
							use_upnp.setEnabled( enabled_not_ext );
							always_socks.setEnabled( plugin_enabled);
							cpu_throttle.setEnabled( enabled_not_ext );
							
							ext_i2p_param.setEnabled( plugin_enabled );
							ext_i2p_host_param.setEnabled( !enabled_not_ext );
							ext_i2p_port_param.setEnabled( !enabled_not_ext );
							
							floodfill_param.setEnabled( enabled_not_ext );
							
							command_text_param.setEnabled( plugin_enabled );
							command_exec_param.setEnabled( plugin_enabled );
						}
					};
			
			enable_param.addListener( enabler_listener );
			dht_enable_param.addListener( enabler_listener );
			dht_sec_enable_param.addListener( enabler_listener );
			
			ext_i2p_param.addListener( enabler_listener );
			link_rates_param.addListener( enabler_listener );
			
			enabler_listener.parameterChanged( null );
				
			final Map<String,Object>	router_properties = new HashMap<String, Object>();
			
			ParameterListener router_config_change_listener = 
				new ParameterListener()
				{
					@Override
					public void
					parameterChanged(
						Parameter param) 
					{
						router_properties.put( I2PHelperRouter.PARAM_SEND_KBS, up_limit_param.getValue());
						router_properties.put( I2PHelperRouter.PARAM_RECV_KBS, down_limit_param.getValue());
						router_properties.put( I2PHelperRouter.PARAM_SHARE_PERCENT, share_percent_param.getValue());
						router_properties.put( I2PHelperRouter.PARAM_FLOODFILL_CONTROL, floodfill_param.getValue());

						router_properties.put( I2PHelperRouter.PARAM_AUTO_QUANTITY_ADJUST, tunnel_auto_quantities_param.getValue());

						router_properties.put( I2PHelperRouter.PARAM_MIX_ENABLED, mix_enabled_param.getValue());
						router_properties.put( I2PHelperRouter.PARAM_MIX_INBOUND_HOPS, mix_in_hops_param.getValue());
						router_properties.put( I2PHelperRouter.PARAM_MIX_INBOUND_QUANTITY, mix_in_quant_param.getValue());
						router_properties.put( I2PHelperRouter.PARAM_MIX_OUTBOUND_HOPS, mix_out_hops_param.getValue());
						router_properties.put( I2PHelperRouter.PARAM_MIX_OUTBOUND_QUANTITY, mix_out_quant_param.getValue());

						router_properties.put( I2PHelperRouter.PARAM_PURE_ENABLED, i2p_enabled_param.getValue());
						router_properties.put( I2PHelperRouter.PARAM_PURE_INBOUND_HOPS, i2p_in_hops_param.getValue());
						router_properties.put( I2PHelperRouter.PARAM_PURE_INBOUND_QUANTITY, i2p_in_quant_param.getValue());
						router_properties.put( I2PHelperRouter.PARAM_PURE_OUTBOUND_HOPS, i2p_out_hops_param.getValue());
						router_properties.put( I2PHelperRouter.PARAM_PURE_OUTBOUND_QUANTITY, i2p_out_quant_param.getValue());

						router_properties.put( I2PHelperRouter.PARAM_OTHER_ENABLED, other_enabled_param.getValue());
						router_properties.put( I2PHelperRouter.PARAM_OTHER_INBOUND_HOPS, other_in_hops_param.getValue());
						router_properties.put( I2PHelperRouter.PARAM_OTHER_INBOUND_QUANTITY, other_in_quant_param.getValue());
						router_properties.put( I2PHelperRouter.PARAM_OTHER_OUTBOUND_HOPS, other_out_hops_param.getValue());
						router_properties.put( I2PHelperRouter.PARAM_OTHER_OUTBOUND_QUANTITY, other_out_quant_param.getValue());

						I2PHelperRouter current_router = router;
						
						if ( current_router != null ){
							
							current_router.updateProperties();
						}
					}
				};
			
			router_config_change_listener.parameterChanged( null );

			up_limit_param.addListener( router_config_change_listener );
			down_limit_param.addListener( router_config_change_listener );
			share_percent_param.addListener( router_config_change_listener );
			
			floodfill_param.addListener( router_config_change_listener );

				// these require a restart but whatever
			
			tunnel_auto_quantities_param.addListener( router_config_change_listener );
			for ( Parameter p: tunnel_params ){
				p.addListener( router_config_change_listener );
			}
			
			
			
			final boolean	is_external_router = ext_i2p_param.getValue();
			
			if ( plugin_enabled ){
				
				log( "Internal port=" + int_port +", external=" + ext_port + ", socks=" + sock_port );

				message_handler = new I2PHelperMessageHandler( I2PHelperPlugin.this );
									
				timer_event = 
					SimpleTimer.addPeriodicEvent(
						"i2phelper:checker",
						60*1000,
						new TimerEventPerformer()
						{	
							private int			tick_count;
							
							private long		last_total;
							private long		last_active;
							
							private boolean		floodfill_capable;
							
							private boolean 	alert_logged;
							
							@Override
							public void 
							perform(
								TimerEvent event) 
							{
								if ( unloaded ){
									
									if ( timer_event != null ){
										
										timer_event.cancel();
									}
									
									return;
								}
								
								tick_count++;
								
								if ( !is_external_router ){
																			// we don't manage rate limits for external routers
	
									int mult;
									
									if ( link_rates_param.getValue()){
										
										mult = 1;
										
									}else{
									
										long[] totals = message_handler.getDataTotals();
										
										long current_total = totals[0] + totals[1];
										
										long	now = SystemTime.getCurrentTime();
										
										if ( current_total != last_total ){
										
											last_total = current_total;
											
											last_active = now;
										}
										
										boolean	is_active = now - last_active <= 5*60*1000;
										
										mult = is_active?limit_multiplier_param.getValue():1;
																					
										if ((!floodfill_capable) && tick_count%10 == 0 ){
																							
											floodfill_capable = tryFloodfill();
										}
											
										if ( floodfill_capable && mult < 2){
												
											mult = 2;
										}
									}
									
									I2PHelperRouter current_router = router;
									
									if ( current_router != null ){
										
										if ( current_router.getRateMultiplier() != mult || current_router.getFloodfillCapable() != floodfill_capable ){
										
											current_router.setRateMultiplier( mult );
											
											current_router.setFloodfillCapable( floodfill_capable );
											
											current_router.updateProperties();
										}
										
										try{
												// net.i2p.router.web.helpers.SummaryHelper skew logic
											
											long skew = current_router.getRouter().getContext().commSystem().getFramedAveragePeerClockSkew( 33 );
											
											log( "Detected router time skew: " + skew );

											skew =  Math.abs( skew );
											
											if ( skew > 30*1000 ){
												
												if ( log != null && !alert_logged ){
													
													alert_logged = true;
													
													log.logAlert(
														LoggerChannel.LT_WARNING,
														"I2P reported a clock skew of " + skew + "ms. Correct your computer time setting if you want I2P to work correctly." );
												}
											}
										}catch( Throwable e ){
											
										}
									}
								}
								
								checkTunnelQuantities();
							}
						});
				
				alt_network_handler = new I2PHelperAltNetHandler();
				
				plugin_interface.addListener(
					new PluginAdapter()
					{
						@Override
						public void
						initializationComplete()
						{
							if ( use_upnp.getValue()){
								
								PluginInterface pi_upnp = plugin_interface.getPluginManager().getPluginInterfaceByClass( UPnPPlugin.class );
								
								if ( pi_upnp == null ){
									
									log( "No UPnP plugin available, not attempting port mapping");
									
								}else{
									
									for ( int i=0;i<2;i++){
										UPnPMapping	mapping = 
											((UPnPPlugin)pi_upnp.getPlugin()).addMapping( 
												plugin_interface.getPluginName(), 
												i==0, 
												f_ext_port, 
												true );
									}
								}
							}else{
									
								log( "UPnP disabled for the plugin, not attempting port mapping");
							}
						}
						
						@Override
						public void
						closedownInitiated()
						{
							unload( true );
						}
					});
					
				new AEThread2("I2P: RouterInit")
				{
					@Override
					public void
					run()
					{
						final boolean	is_bootstrap_node	= false;
						final boolean	is_vuze_dht			= true;
										
						while( !unloaded ){
														
							try{
								I2PHelperRouter my_router	= null;

								try{	
									boolean new_id = COConfigurationManager.getBooleanParameter( "azi2phelper.new.identity.required", false );

									my_router = router = 
											new I2PHelperRouter( I2PHelperPlugin.this, plugin_dir, router_properties, is_bootstrap_node, is_vuze_dht, new_id, dht_count, I2PHelperPlugin.this );
									
									if ( ext_i2p_param.getValue()){
											
										my_router.initialiseRouter( ext_i2p_host_param.getValue(), ext_i2p_port_param.getValue());
													
									}else{
										
										my_router.initialiseRouter( f_int_port, f_ext_port );
									}
										
									my_router.initialiseDHTs();
									
									if ( new_id ){
									
										COConfigurationManager.setParameter( "azi2phelper.new.identity.required", false );
									
										COConfigurationManager.save();
									}
									
									if ( !unloaded ){
										
										boolean	first_run = true;
										
										network_mixer = new I2PHelperNetworkMixer( I2PHelperPlugin.this, net_mix_enable, seed_request_enable, net_mix_incomp_num, net_mix_comp_num );

										tracker = new I2PHelperTracker( I2PHelperPlugin.this, my_router, network_mixer );
										
										router_init_sem.releaseForever();
										
										while( !unloaded ){
											
											if ( first_run ){
												
												if ( always_socks.getValue()){
													
													try{
														getSocksProxy();
														
													}catch( Throwable e ){
														
													}
												}
											}
											
											try{
												my_router.logInfo();
											
											}catch( Throwable e ){
												
											}
											
											Thread.sleep(60*1000);
											
											first_run = false;
										}
									}
									
								}finally{
									
									if ( my_router != null ){
										
										my_router.destroy();
			
										if ( router == my_router ){
																		
											router = null;
										}
									}
								}
							}catch( Throwable e ){
								
								log( "Router initialisation fail: " + Debug.getNestedExceptionMessage( e ));
								
								if ( !unloaded ){
										
									try{
										Thread.sleep(15*1000);
										
									}catch( Throwable f ){
									}
								}
							}
						}
					}
				}.start();
				
				plugin_interface.getUIManager().addUIListener(
						new UIManagerListener()
						{
							@Override
							public void
							UIAttached(
								UIInstance		instance )
							{
								if ( instance.getUIType().equals(UIInstance.UIT_SWT) ){
									
									ui_view = 
										new I2PHelperView( 
											I2PHelperPlugin.this, 
											instance, 
											"azi2phelper.name",
											icon_enable );
								}
							}

							@Override
							public void
							UIDetached(
								UIInstance		instance )
							{
								if ( instance.getUIType().equals(UIInstance.UIT_SWT) ){
									if (ui_view != null) {
										ui_view.unload();
										ui_view = null;
									}
								}
							}
						});
				
				MagnetURIHandler uri_handler = MagnetURIHandler.getSingleton();

				uri_handler.addListener( magnet_handler );
				
				pi.addEventListener(
						new PluginEventListener()
						{
							@Override
							public void
							handleEvent(
								PluginEvent	ev )
							{
								int	type = ev.getType();
								
								if ( type == PluginEvent.PEV_ALL_PLUGINS_INITIALISED ){
									
									PluginInterface[] pis = plugin_interface.getPluginManager().getPluginInterfaces();
									
									for ( PluginInterface pi: pis ){
										
										String id = pi.getPluginID();
										
										if ( id.equals( "aznettorbrowser" )){
											
											torBrowserPluginInstalled( pi );
										}
									}
									
								}else if ( 	type == PluginEvent.PEV_PLUGIN_INSTALLED ||
											type == PluginEvent.PEV_PLUGIN_UPDATED ){
									
									String id = (String)ev.getValue();
									
									if ( id.equals( "aznettorbrowser" )){ 
										
										torBrowserPluginInstalled( plugin_interface.getPluginManager().getPluginInterfaceByID( id ));
										
									}else if ( id.equals( "aznettor" )){ 
										
										torPluginInstalled( plugin_interface.getPluginManager().getPluginInterfaceByID( id ));
									}
								}							
							}
						});

			}else{
								
				log( "Plugin is disabled" );
			}
		}catch( Throwable e ){
			
			synchronized( this ){
			
				unloaded = true;
			}
			
			Debug.out( e );
			
			if ( e instanceof PluginException ){
				
				throw((PluginException)e);
			}
		}
	}
	
	private int 		cpu_throttle_factor;
	private boolean		cpu_throttle_init_done;
	
	public void
	setModPowLimiter()
	{
		synchronized( this ){
			
			if ( cpu_throttle_init_done ){
				
				return;
			}
			
			cpu_throttle_init_done = true;
		}
		
		NativeBigInteger.setModPowLimiter(
				new NativeBigInteger.ModPowLimiter()
				{
					private final int num_processors = Math.max( 1, Runtime.getRuntime().availableProcessors());
					
					private final int ms_total = 1000*num_processors;
					
					private long	start_time;
					private int		last_tf;
					
					private long	call_time;
					private long	call_count;
					
					private long	current_sleep = -1;
					private long	last_logged_sleep;
					
					@Override
					public void 
					handleCall(
						long 	start,
						long	end )
					{
						synchronized( this ){
							
							if ( cpu_throttle_factor != last_tf ){
								
								call_time			= 0;
								call_count			= 0;
								current_sleep		= -1;
								last_logged_sleep 	= 0;
								
								last_tf = cpu_throttle_factor;
							}
							
							if ( last_tf <= 0 ){
								
								return;
								
							}
							
							call_count++;
							
							if ( call_count == 1 ){
								
								start_time = SystemTime.getMonotonousTime();
							}
							
							call_time	+= end - start;
															
							long	sleep;
							
							if ( current_sleep == -1 || call_count == 1000 ){
															
								long average = (call_time/call_count)/1000000;
								
								if ( average == 0 ){
									
									average = 1;
								}
								
								int ms_avail = ms_total/last_tf;	// allowable cpu

								long calls_per_sec = ms_avail/average;
								
								int SLEEP_MAX = 100;
								
								if ( calls_per_sec == 0 ){
								
									sleep = SLEEP_MAX;
									
								}else{
									
									sleep = 1000/calls_per_sec;
								}
								
								if ( sleep <= 0 ){
									
									sleep = 1;
									
								}else if ( sleep > SLEEP_MAX ){
									
									sleep = SLEEP_MAX;
								}
								
								if ( call_count == 1000 ){
									
									long	elapsed_sec = (SystemTime.getMonotonousTime() - start_time)/1000;
									
									if ( elapsed_sec == 0 ){
										
										elapsed_sec = 1;
									}
									
									long call_per_sec = call_count /elapsed_sec;
									
									call_time 	= 0;
									call_count	= 0;
									
									current_sleep = sleep;
									
									if ( sleep != last_logged_sleep ){
									
										last_logged_sleep = sleep;
										
										log( "CPU throttle: factor=" + cpu_throttle_factor + "+" + call_per_sec + "/s -> " + sleep + "ms" );
									}
								}
							}else{
								
								sleep = current_sleep;
							}
																													
							try{
								Thread.sleep( sleep );
								
							}catch( Throwable e ){
								
							}
						}
					}
				});
			
	}
	
	private void
	torPluginInstalled(
		PluginInterface tor_plugin_pi )
	{
		try{
			PluginInterface tor_browser_pi = plugin_interface.getPluginManager().getPluginInterfaceByID( "aznettorbrowser" );

			if ( tor_browser_pi != null ){
				
				I2PHelperSocksProxy proxy = getSocksProxy();
			
				int	socks_port = proxy.getPort();
									
				Map<String,Object>	config = new HashMap<String, Object>();
					
				config.put( "i2p_socks_port", socks_port );
					
				tor_plugin_pi.getIPC().invoke( "setConfig", new Object[]{ config });
			}
		}catch( Throwable e ){
			
			Debug.out( e );
		}
	}
	
	private void
	torBrowserPluginInstalled(
		PluginInterface tor_browser_pi )
	{
		try{
			PluginInterface tor_plugin_pi = plugin_interface.getPluginManager().getPluginInterfaceByID( "aznettor" );

			if ( tor_plugin_pi != null ){
				
				I2PHelperSocksProxy proxy = getSocksProxy();
			
				int	socks_port = proxy.getPort();
									
				Map<String,Object>	config = new HashMap<String, Object>();
					
				config.put( "i2p_socks_port", socks_port );
					
				tor_plugin_pi.getIPC().invoke( "setConfig", new Object[]{ config });
			}
		}catch( Throwable e ){
			
			Debug.out( e );
		}
	}
	
	@Override
	public PluginInterface
	getPluginInterface()
	{
		return( plugin_interface );
	}
	
	public boolean
	isEnabled()
	{
		return( plugin_enabled );
	}
	
	@Override
	public boolean 
	isDHTEnabled()
	{
		return( dht_enabled );
	}
	
	public String
	getStatusText()
	{
		if ( plugin_enabled ){
			
			I2PHelperRouter	r = router;
			
			if ( r == null ){
				
				return(  getMessageText( "azi2phelper.status.initialising" ));
				
			}else{
				
				return( r.getStatusText());
			}
			
		}else{
			
			return( getMessageText( "azi2phelper.status.disabled" ));
		}
	}
	
	@Override
	public String
	getMessageText(
		String		key )
	{
		return( loc_utils.getLocalisedMessageText(key));
	}
	
	@Override
	public String
	getMessageText(
		String		key,
		String...	args )
	{
		return( loc_utils.getLocalisedMessageText(key, args));
	}
	
	@Override
	public void
	stateChanged(
		I2PHelperRouterDHT	dht,
		boolean				init_done )
	{
		if ( !dht.isSecondary()){
		
			if ( init_done ){
				
				if ( dht.getDHTIndex() == I2PHelperRouter.DHT_MIX ){
							
					new AEThread2( "I2P:async" )
					{
						@Override
						public void
						run()
						{
							String[] nets = new String[]{ AENetworkClassifier.AT_PUBLIC, AENetworkClassifier.AT_I2P };
							
							Map<String,Object> server_options = new HashMap<String, Object>();
							
							server_options.put( "networks", nets );
							
							try{
								DHTPluginInterface expected_dht_pi = getProxyDHT( "DHT bridge setup", server_options );
								
								if ( expected_dht_pi != null ){
									
									List<DistributedDatabase> ddbs = getPluginInterface().getUtilities().getDistributedDatabases( nets );
					
									for ( DistributedDatabase ddb: ddbs ){
										
										if ( ddb.getNetwork() == AENetworkClassifier.AT_I2P ){
												
											DHTPluginInterface dht_pi = ddb.getDHTPlugin();
											
											if ( dht_pi == expected_dht_pi ){
											
												while( dht_pi.isInitialising()){
													
													Thread.sleep( 5000 );
												}
												
												dht_bridge.setDDB( ddb );
											}
										}
									}
								}
							}catch( Throwable e ){
								
								Debug.out( e );
							}
						}
					}.start();
				}
			}
		}
					
		String address = dht.getB32Address();
			
		if ( address != null && address.length() > 0 ){
				
			int	 index = dht.getDHTIndex();
			
			if ( dht.isSecondary()){
				
				dht_addresses2[ index ] = address;
				
			}else{
			
				dht_addresses[ index ] = address;
			}
			
			String str = "";
			
			for ( int i=0;i<dht_addresses.length;i++){
				
				str += ( str.length()==0?"":"\r\n" ) + (dht_addresses[i]==null?"<pending>":dht_addresses[i]);
			}
						
			for ( int i=0;i<dht_addresses2.length;i++){
				
				String addr = dht_addresses2[i];
				
				if ( addr != null && addr.length() > 0 ){
					
					str += ( str.length()==0?"":"\r\n" ) + addr + "+";
				}
			}
			
			i2p_address_param.setValue( str );
		}
	}
	
	private void
	updatePortInfo()
	{
		String	socks_port;
		
		synchronized( this ){
			
			if ( socks_proxy != null ){
				
				socks_port = "" + socks_proxy.getPort();
				
			}else{
				
				socks_port = "inactive";
			}
		}
		
		port_info_param.setValue( active_int_port + "/" + active_ext_port + "/" + socks_port );
	}
	
	public I2PHelperRouter
	getRouter()
	{
		return( router );
	}
	
	private static Map<ServerInstance,DHTAZClient>	az_dht_client_map = new HashMap<ServerInstance, DHTAZClient>();
	
	private static DHTAZClient
	getAZDHTClient(
		I2PHelperDHT		dht,
		ServerInstance		inst,
		I2PHelperAdapter	adapter )
	{
		synchronized( az_dht_client_map ){
		
			DHTAZClient	client = az_dht_client_map.get( inst );
			
			if ( client == null ){
				
				client = new DHTAZClient( inst, dht.getHelperAZDHT(), 10000, adapter );
				
				az_dht_client_map.put( inst, client );
			}
			
			return( client );
		}
	}
	
	public int
	getDHTCount()
	{
		return( dht_count );
	}
	
	public boolean
	getDHTSecondariesEnabled()
	{
		return( dht_secondaries_enabled );
	}
	
	public InetSocketAddress
	getSecondaryEndpoint(
		int		dht_index )
	{
		if ( dht_secondaries_enabled ){
			
			I2PHelperRouter	r = router;

			if ( r != null ){
				
				I2PHelperRouterDHT dht = r.selectSecondaryDHT( dht_index );
				
				if ( dht != null ){
							
					return( InetSocketAddress.createUnresolved( dht.getB32Address(), 6881 ));
				}
			}
		}
		
		return( null );
	}
	
	int		max_mix_peers	= 0;
	int		max_pure_peers	= 0;
	long	info_start_time = SystemTime.getMonotonousTime();
	boolean	info_first		= true;
	String	uptime_history	= "";
	
	private void
	checkTunnelQuantities()
	{
		if ( SystemTime.getMonotonousTime() - info_start_time < 5*60*1000 ){
			
			return;
		}
		
		if ( info_first ){
			
			info_first 		= false;
			max_mix_peers	= 0;
			max_pure_peers	= 0;
		}
		
		List<DownloadManager> dms = CoreFactory.getSingleton().getGlobalManager().getDownloadManagers();
		
		int	mix_peers 	= 0;
		int	pure_peers 	= 0;
		
		for ( DownloadManager dm: dms ){
			
			int state = dm.getState();
			
			if ( state == DownloadManager.STATE_DOWNLOADING || state == DownloadManager.STATE_SEEDING ){
			
				String[] nets = dm.getDownloadState().getNetworks();
				
				boolean is_i2p = false;
				boolean is_pub = false;
				
				for ( String net: nets ){
					
					if ( net == AENetworkClassifier.AT_I2P ){
						
						is_i2p = true;
						
					}else if ( net == AENetworkClassifier.AT_PUBLIC ){
						
						is_pub = true;
					}
				}
				
				if ( is_i2p ){
					
					PEPeerManager pm = dm.getPeerManager();
					
					if ( pm != null ){
						
						List<PEPeer> peers = pm.getPeers();
					
						for ( PEPeer peer: peers ){
							
							int peer_state = peer.getPeerState();
							
							if ( peer_state == PEPeer.HANDSHAKING || peer_state == PEPeer.TRANSFERING ){
								
								if ( AENetworkClassifier.categoriseAddress( peer.getIp()) == AENetworkClassifier.AT_I2P ){
									
									if ( is_pub ){
										
										mix_peers++;
										
									}else{
										
										pure_peers++;
									}
								}
							}
						}
					}
				}
			}
		}
		
		max_mix_peers 	= Math.max( max_mix_peers, mix_peers );
		max_pure_peers 	= Math.max( max_pure_peers, pure_peers );
		
		I2PHelperRouter	r = router;

		if ( r != null ){
			
			if ( r.getBooleanParameter( I2PHelperRouter.PARAM_AUTO_QUANTITY_ADJUST )){
				
				I2PHelperRouterDHT[] dhts = r.getAllDHTs();
				
				for ( I2PHelperRouterDHT dht: dhts ){
		
					if ( !dht.isDHTInitialised()){
						
						continue;
					}
					
					if ( dht.getDHTIndex() == I2PHelperRouter.DHT_MIX ){
					
						int min = Math.max( r.getIntegerParameter( I2PHelperRouter.PARAM_MIX_INBOUND_QUANTITY ),  r.getIntegerParameter( I2PHelperRouter.PARAM_MIX_OUTBOUND_QUANTITY ));
						
						dht.updatePeerCount( mix_peers, min );
						
					}else{
					
						int min = Math.max( r.getIntegerParameter( I2PHelperRouter.PARAM_PURE_INBOUND_QUANTITY ),  r.getIntegerParameter( I2PHelperRouter.PARAM_PURE_OUTBOUND_QUANTITY ));
	
						dht.updatePeerCount( pure_peers, min );
					}
				}
			}
		}
		
		writePluginInfo();
	}
	
	private boolean
	tryFloodfill()
	{
			// See FloodfillMonitorJob.java for details of how the router handles floodfill activation
		
		I2PHelperRouter	r = router;

		if ( r == null ){
			
			return( false );
		}
		
		Router i2p_router = r.getRouter();
		
		if ( i2p_router == null ){
			
			return( false );
		}
		
		long uptime = i2p_router.getUptime();
		
		if ( uptime < 2*60*60*1000 ){
			
			return( false );
		}
		
		String caps = i2p_router.getRouterInfo().getCapabilities();
		
		if ( caps.contains( "f" ) || caps.contains( "U" )){
			
			return( false );
		}
		
			// check we have a history of being up for a reasonable time
		
		if ( uptime < 6*60*60*1000 ){
			
			boolean ok = false;
			
			String[] ups = uptime_history.split( ";" );
			
			for ( String up: ups ){
				
				if ( !up.isEmpty()){
					
					try{
						int mins = Integer.parseInt( up );
					
						if (  mins >= 360 ){
							
							ok = true;
							
							break;
						}
					}catch( Throwable e ){
						
					}
				}
			}
			
			if ( !ok ){
				
				return( false );
			}
		}
		
		LongTermStats lts = StatsFactory.getLongTermStats();
		
		if ( lts == null ){
			
			return( false );
		}
		
		long[] stats = lts.getTotalUsageInPeriod( LongTermStats.PT_SLIDING_WEEK, 1.0 );
		
		long total_up = stats[LongTermStats.ST_PROTOCOL_UPLOAD] + stats[LongTermStats.ST_DATA_UPLOAD];
		long total_do = stats[LongTermStats.ST_PROTOCOL_DOWNLOAD] + stats[LongTermStats.ST_DATA_DOWNLOAD];

		total_up = total_up/(1024*1024);	// MB
		total_do = total_do/(1024*1024);
		
		if ( total_up + total_do < 16*1024 ){
			
				// < 16gb in the last week, ignore
			
			return( false );
		}
		
		return( true );
	}
	
	private void
	readPluginInfo()
	{
		PluginConfig pc = plugin_interface.getPluginconfig();
		
		String str = pc.getPluginStringParameter( "plugin.info", "" );
		
		if ( str.length() > 0 ){
			
			String[] bits = str.split( "/" );
			
			if ( bits.length >= 3 ){
				
				String ver = bits[0];
				
				if ( ver.equals( "1" ) || ver.equals( "2" )){
						
					max_mix_peers 	= Integer.parseInt( bits[1] );
					max_pure_peers 	= Integer.parseInt( bits[2] );
				}
				
				if ( ver.equals( "3" ) && bits.length > 12 ){
										
					uptime_history = bits[12];
				}
			}
		}
	}
	

	private void
	writePluginInfo()
	{
		// version 2 max_mix_peers max_pure_peers {router params} {dhts started}
		// version 3 added capabilities, uptime hist, xfer stats
		
		String plugin_info = "3" + "/" + max_mix_peers + "/" + max_pure_peers;
				
		I2PHelperRouter	r = router;

		if ( r != null ){
			
			String[] params = {
					I2PHelperRouter.PARAM_SEND_KBS, I2PHelperRouter.PARAM_RECV_KBS, I2PHelperRouter.PARAM_SHARE_PERCENT,
					I2PHelperRouter.PARAM_MIX_INBOUND_HOPS, I2PHelperRouter.PARAM_MIX_INBOUND_QUANTITY,
					I2PHelperRouter.PARAM_PURE_INBOUND_HOPS, I2PHelperRouter.PARAM_PURE_INBOUND_QUANTITY };
			
			for ( String param: params ){
				
				plugin_info += "/" + r.getIntegerParameter( param );
			}
			
			I2PHelperRouterDHT[] dhts = r.getAllDHTs();
			
			String sep = "/";
			
			for ( I2PHelperRouterDHT dht: dhts ){
				
				plugin_info += sep + dht.isDHTStarted();
				
				sep = ";";
			}
			
			Router i2p_router = r.getRouter();
			
			String 	rinf 	= null;
			
			String up_str = uptime_history;
			
			if ( i2p_router != null ){
				
				try{
					rinf 	= i2p_router.getRouterInfo().getCapabilities();
					
					if ( r.getFloodfillCapable()){
						
						rinf += "+";
					}
					
					long up = i2p_router.getUptime();
					
					up_str += (up_str.isEmpty()?"":";") + (up/(60*1000));
					
					String[] bits = up_str.split( ";" );
					
					if ( bits.length > 5 ){
						
						up_str = "";
						
						for ( int i=bits.length-5;i<bits.length;i++){
							
							up_str += (up_str.isEmpty()?"":";") + bits[i];
						}
					}
				}catch( Throwable e ){
				}
			}
			
			plugin_info += "/" + (rinf==null?"-":rinf);	
			plugin_info += "/" + up_str;
		}
		
		LongTermStats lts = StatsFactory.getLongTermStats();
		
		if ( lts != null ){
			
			long[] stats = lts.getTotalUsageInPeriod( LongTermStats.PT_SLIDING_WEEK, 1.0 );
			
			long total_up = stats[LongTermStats.ST_PROTOCOL_UPLOAD] + stats[LongTermStats.ST_DATA_UPLOAD];
			long total_do = stats[LongTermStats.ST_PROTOCOL_DOWNLOAD] + stats[LongTermStats.ST_DATA_DOWNLOAD];
	
			total_up = total_up/(1024*1024);
			total_do = total_do/(1024*1024);
			
			plugin_info += "/" + total_up + ";" + total_do;
		}
		
		//System.out.println( plugin_info );
		
		PluginConfig pc = plugin_interface.getPluginconfig();
		
		if ( !pc.getPluginStringParameter( "plugin.info", "" ).equals( plugin_info )){
			
			pc.setPluginParameter( "plugin.info", plugin_info );
		
			COConfigurationManager.save();
		}
	}
	
	@Override
	public void
	contactAlive(
		I2PHelperDHT 				dht,
		DHTTransportContactI2P 		contact) 
	{
		if ( dht.getDHTIndex() == I2PHelperRouter.DHT_MIX ){
			
			I2PHelperAltNetHandler net = alt_network_handler;
			
			if ( net != null ){
				
				net.contactAlive( contact );
			}
		}
	}
	
	private I2PHelperSocksProxy
	getSocksProxy()
	
		throws IPCException
	{
		if ( !plugin_enabled ){
			
			throw( new IPCException( "Plugin disabled" ));
		}
		
		synchronized( I2PHelperPlugin.this ){
			
			if ( socks_proxy == null ){
			
				if ( router == null ){
					
					throw( new IPCException( "Router unavailable" ));
				}
				
				try{
					int	explicit_port = socks_port_param.getValue();
					
					int	port;
					
					if ( explicit_port == 0 ){
						
						port = plugin_config.getPluginIntParameter( "azi2phelper.socks.port.last", 0 );

					}else{
						
						port = explicit_port;
					}
					
					try{
						socks_proxy = new I2PHelperSocksProxy( router, port, socks_allow_public_param.getValue(), I2PHelperPlugin.this );
						
					}catch( Throwable e ){
						
						if ( explicit_port == 0 ){
							
							socks_proxy = new I2PHelperSocksProxy( router, 0, socks_allow_public_param.getValue(), I2PHelperPlugin.this );
							
						}else{
							
							throw( e );
						}
					}
				
					if ( explicit_port == 0 ){
						
						plugin_config.setPluginParameter( "azi2phelper.socks.port.last", socks_proxy.getPort());

					}
					updatePortInfo();
					
				}catch( Throwable e ){
				
					throw( new IPCException( e ));
				}
			}
			
			return( socks_proxy );
		}
	}
	
	private static void
	executeCommand(
		String				cmd_str,
		I2PHelperRouter		router,
		I2PHelperTracker	tracker,
		I2PHelperAdapter	adapter,
		I2PHelperPlugin		plugin_maybe_null )
		
		throws Exception
	{
		cmd_str = cmd_str.trim();
		
		String[] bits = cmd_str.split( "\\s+" );

		if ( bits.length == 0 ){
			
			adapter.log( "No command" );
			
		}else if ( router == null ){
			
			adapter.log( "Router is not initialised" );
			
		}else{
		
			I2PHelperDHT dht = router.selectDHT().getDHT();
			
			if ( dht == null ){
				
				adapter.log( "DHT is not initialised" );
				
			}else{
				
				String cmd = bits[0].toLowerCase();
				
				if ( cmd.equals( "print" )){
				
					dht.print();
				
				}else if ( cmd.equals( "info" )){
						
					router.logInfo();
					
				}else if ( cmd.equals( "lookup" )){
					
					if ( bits.length != 2 ){
						
						throw( new Exception( "usage: lookup <hash>"));
					}
					
					byte[] hash = decodeHash( bits[1] );

					Destination dest = router.lookupDestination( hash );
					
					if ( dest == null ){
						
						adapter.log( "lookup failed" );
						
					}else{
					
						adapter.log( "lookup -> " + dest.toBase64());
					}		
				}else if ( cmd.equals( "get" )){
					
					if ( bits.length != 2 ){
						
						throw( new Exception( "usage: get <base16_infohash>"));
					}
					
					byte[] hash = decodeHash( bits[1] );
				
					tracker.get( hash );
				
				}else if ( cmd.equals( "put" )){
					
					if ( bits.length != 2 ){
						
						throw( new Exception( "usage: put <base16_infohash>"));
					}
					
					byte[] hash = decodeHash( bits[1] );
				
					tracker.put( hash );

				}else if ( cmd.equals( "ping_dest" ) || cmd.equals( "az_ping_dest" )){
					
					if ( bits.length != 3 ){
					
						throw( new Exception( "usage: ping_dest <base64_dest> <dht_port>"));
					}
					
					String dest_64 = bits[1];
					
					int		port 	= Integer.parseInt( bits[2] );
					
					Destination dest = new Destination();
					
					dest.fromBase64( dest_64 );
					
					dht.ping( dest, port, cmd.startsWith( "az" ));
				
				}else if ( cmd.equals( "ping_all_dest" ) || cmd.equals( "az_ping_all_dest" )){
					
					if ( bits.length != 1 ){
					
						throw( new Exception( "usage: [az_]ping_all_dest"));
					}
					
					dht.pingAll( cmd.startsWith( "az" ));
				
				}else if ( cmd.equals( "az_find_node" )){

					if ( bits.length != 4 ){
						
						throw( new Exception( "usage: az_find_node <base64_dest> <dht_port> node_id"));
					}
					
					String dest_64 = bits[1];
					
					int		port 	= Integer.parseInt( bits[2] );
					
					Destination dest = new Destination();
					
					dest.fromBase64( dest_64 );
					
					byte[]	node_id = decodeHash( bits[3] ); 
							
					dht.findNode( dest, port, node_id );
					
				}else if ( cmd.equals( "az_find_value" )){

					if ( bits.length != 4 ){
						
						throw( new Exception( "usage: az_find_value <base64_dest> <dht_port> id"));
					}
					
					String dest_64 = bits[1];
					
					int		port 	= Integer.parseInt( bits[2] );
					
					Destination dest = new Destination();
					
					dest.fromBase64( dest_64 );
					
					byte[]	node_id = decodeHash( bits[3] ); 
							
					dht.findValue( dest, port, node_id );
					
				}else if ( cmd.equals( "az_store" )){

					if ( bits.length != 5 ){
						
						throw( new Exception( "usage: az_store <base64_dest> <dht_port> key value"));
					}
					
					String dest_64 = bits[1];
					
					int		port 	= Integer.parseInt( bits[2] );
					
					Destination dest = new Destination();
					
					dest.fromBase64( dest_64 );
					
					byte[]	key = decodeHash( bits[3] ); 
							
					byte[] value = bits[4].getBytes( "UTF-8" );
					
					dht.store( dest, port, key, value );
					
				}else if ( cmd.equals( "az_put" )){

					if ( bits.length != 3 ){
						
						throw( new Exception( "usage: az_put key value"));
					}
					
					byte[]	key = decodeHash( bits[1] ); 
							
					byte[] value = bits[2].getBytes( "UTF-8" );
					
					dht.getHelperAZDHT().put(
						key, "az_put", value, I2PHelperAZDHT.FLAG_NON_ANON, true, 
						new I2PHelperAZDHT.OperationAdapter() {
							
							@Override
							public void
							valueWritten(
								DHTPluginContact		target,
								DHTPluginValue			value )
							{
								System.out.println( "az_put: write to " + target.getAddress());
							}
							
							@Override
							public void
							complete(
								byte[]				key,
								boolean				timeout_occurred )
							{
								System.out.println( "az_put complete" );
							}
						});

				}else if ( cmd.equals( "az_get" )){

					if ( bits.length != 2 ){
						
						throw( new Exception( "usage: az_get key"));
					}
					
					byte[]	key;
					
					if ( bits[1].equals( "az-chat-key" )){
						
						key = "az-chat-key".getBytes();
						
					}else{
						
						key = decodeHash( bits[1] ); 
					}	
					
					dht.getHelperAZDHT().get(
						key, "az_get", I2PHelperAZDHT.FLAG_NONE, 16, 3*60*1000, false, true, 
						new I2PHelperAZDHT.OperationAdapter() {
							
							@Override
							public void
							valueRead(
								DHTPluginContact		target,
								DHTPluginValue			value )
							{
								try{
									System.out.println( "az_get: read from " + target.getAddress() + ", value=" + new String( value.getValue(), "UTF-8" ));
									
								}catch( Throwable e ){
									
									e.printStackTrace();
								}
							}
							
							@Override
							public void
							complete(
								byte[]				key,
								boolean				timeout_occurred )
							{
								System.out.println( "az_get complete" );
							}
						});
					
				}else if ( cmd.equals( "az_chat_put" )){

					if ( bits.length != 2 ){
						
						throw( new Exception( "usage: az_chat_put <nick>"));
					}
					
					String nick = bits[1];
					
					ServerInstance inst = 
						router.createServer( 
							"chat_" + nick,
							false,
							I2PHelperRouter.SM_TYPE_OTHER,
							new I2PHelperRouter.ServerAdapter()
							{							
								@Override
								public void incomingConnection(ServerInstance server, I2PSocket socket)
										throws Exception {
									System.out.println( "chat incoming, no way" );
								}
							});
					
					DHTAZClient client = getAZDHTClient( dht, inst, adapter ); 
					
					if ( client.waitForInitialisation( 5000 )){
						
						byte[] key 		= ("az-chat-key:" + nick ).getBytes();
						byte[] value 	= "I'm the man".getBytes();
						
						client.put(
								key, "az_chat_put", value, I2PHelperAZDHT.FLAG_NON_ANON, true, 
								new I2PHelperAZDHT.OperationAdapter() {
									
									@Override
									public void
									valueWritten(
										DHTPluginContact		target,
										DHTPluginValue			value )
									{
										System.out.println( "az_chat_put: write to " + target.getAddress());
									}
									
									@Override
									public void
									complete(
										byte[]				key,
										boolean				timeout_occurred )
									{
										System.out.println( "az_chat_put complete" );
									}
								});
					}else{
						
						System.out.println( "chat dht not init" );
					}
					
				}else if ( cmd.equals( "az_chat_get" )){

					if ( bits.length != 3 ){
						
						throw( new Exception( "usage: az_chat_get <my_nick> <their_nick>"));
					}
					
					String my_nick 		= bits[1];
					String their_nick 	= bits[2];

					final ServerInstance inst = 
							router.createServer( 
								"chat_" + my_nick,
								false,
								I2PHelperRouter.SM_TYPE_OTHER,
								new I2PHelperRouter.ServerAdapter()
								{							
									@Override
									public void incomingConnection(ServerInstance server, I2PSocket socket)
											throws Exception {
										System.out.println( "chat incoming, no way" );
									}
								});
					
					DHTAZClient client = getAZDHTClient( dht, inst, adapter ); 
					
					if ( client.waitForInitialisation( 5000 )){

						byte[] key 		= ("az-chat-key:" + their_nick ).getBytes();
						
						client.get(
								key, "az_chat_get", I2PHelperAZDHT.FLAG_NONE, 16, 3*60*1000, false, true, 
								new I2PHelperAZDHT.OperationAdapter(){
									
									@Override
									public void
									valueRead(
										DHTContact		target,
										final DHTValue	value )
									{
										try{
											System.out.println( "az_chat_get: read from " + target.getAddress() + ", value=" + new String( value.getValue(), "UTF-8" ) + ", orig=" + value.getOriginator().getAddress());
											
											new AEThread2("")
											{
												@Override
												public void
												run()
												{
													try{
														inst.connect( value.getOriginator().getAddress().getHostName(), 80, new HashMap<String,Object>());
														
													}catch( Throwable e ){
														
														e.printStackTrace();
													}
												}
											}.start();
										}catch( Throwable e ){
											
											e.printStackTrace();
										}
									}
									
									@Override
									public void
									complete(
										byte[]				key,
										boolean				timeout_occurred )
									{
										System.out.println( "az_chat_get complete" );
									}
								});
					}else{
						
						System.out.println( "chat dht not init" );
					}
					
				}else if ( cmd.equals( "az_pi_put" )){

					if ( bits.length != 2 ){
						
						throw( new Exception( "usage: az_pi_put <key>"));
					}
					
					DHTPluginInterface client = plugin_maybe_null.getProxyDHT( "test", new HashMap<String,Object>());
						
					byte[] key 		= ("az-pi-key:" + bits[1] ).getBytes();
					byte[] value 	= "I'm the pie".getBytes();
					
					client.put(
							key, "az_pi_put: " + bits[1], value, (byte)I2PHelperAZDHT.FLAG_NON_ANON,
							new I2PHelperAZDHT.OperationAdapter() {
								
								@Override
								public void
								valueWritten(
									DHTPluginContact		target,
									DHTPluginValue			value )
								{
									System.out.println( "az_pi_put: write to " + target.getAddress());
								}
								
								@Override
								public void
								complete(
									byte[]				key,
									boolean				timeout_occurred )
								{
									System.out.println( "az_pi_put complete" );
								}
							});
				
					
				}else if ( cmd.equals( "az_pi_get" )){

					if ( bits.length != 2 ){
						
						throw( new Exception( "usage: az_pi_get <key>"));
					}
					
					DHTPluginInterface client = plugin_maybe_null.getProxyDHT( "test", new HashMap<String,Object>());

					byte[] key 		= ("az-pi-key:" + bits[1] ).getBytes();
					
					client.get(
							key, "az_pi_get: " + bits[1], (byte)I2PHelperAZDHT.FLAG_NONE, 
							16, 3*60*1000, false, true, 
							new I2PHelperAZDHT.OperationAdapter(){
								
								@Override
								public void
								valueRead(
									DHTContact		target,
									final DHTValue	value )
								{
									try{
										System.out.println( "az_pi_get: read from " + target.getAddress() + ", value=" + new String( value.getValue(), "UTF-8" ) + ", orig=" + value.getOriginator().getAddress());
										
									}catch( Throwable e ){
										
										e.printStackTrace();
									}
								}
								
								@Override
								public void
								complete(
									byte[]				key,
									boolean				timeout_occurred )
								{
									System.out.println( "az_pi_get complete" );
								}
							});
					
				}else if ( cmd.equals( "az_pi_lookup" )){

					if ( bits.length != 2 ){
						
						throw( new Exception( "usage: az_pi_lookup <hash>"));
					}
					
					byte[] torrent_hash = ByteFormatter.decodeString( bits[1] );
					
					Map<String,Object>	options = new HashMap<String, Object>();
					
					options.put( "server_id", "Scraper" );
					options.put( "server_id_transient", true );
					
					IPCInterface callback = new IPCInterface() {
						
						@Override
						public Object 
						invoke(
							String 		methodName, 
							Object[]	params )
							
							throws IPCException 
						{
							System.out.println( "Callback: " + methodName + "/" + params[0] );
							
							return null;
						}
						
						@Override
						public boolean 
						canInvoke(
							String methodName, 
							Object[] params) 
						{
							return( true );
						}
					};
					
					plugin_maybe_null.lookupTorrent( 
						"test lookup", 
						torrent_hash, 
						options, 
						callback );


				}else if ( cmd.equals( "ddb_setup" )){


					List<DistributedDatabase> ddbs = plugin_maybe_null.getPluginInterface().getUtilities().getDistributedDatabases(
						new String[] {AENetworkClassifier.AT_I2P} );
					
					System.out.println( "DDBs=" + ddbs.size());
					
					for ( final DistributedDatabase ddb: ddbs ){
						
						System.out.println( "Adding handler for " + ddb );
						
						ddb.addTransferHandler(
							test_xfer,
							new DistributedDatabaseTransferHandler() {
								
								@Override
								public DistributedDatabaseValue 
								write(
									DistributedDatabaseContact 			contact,
									DistributedDatabaseTransferType 	type, 
									DistributedDatabaseKey 				key,
									DistributedDatabaseValue 			value ) 
												
									throws DistributedDatabaseException 
								{
									return( null );
								}
								
								@Override
								public DistributedDatabaseValue 
								read(
									DistributedDatabaseContact 			contact,
									DistributedDatabaseTransferType 	type, 
									DistributedDatabaseKey 				key )
									
									throws DistributedDatabaseException 
								{
									return( ddb.createValue( new byte[40000] ));
								}
							});
					}

				}else if ( cmd.equals( "ddb_get" )){

					if ( bits.length != 3 ){
						
						throw( new Exception( "usage: az_chat_get <my_nick> <their_nick>"));
					}
					
					String i2p_address 		= bits[1];
					String i2p_port		 	= bits[2];
					
					List<DistributedDatabase> ddbs = plugin_maybe_null.getPluginInterface().getUtilities().getDistributedDatabases(
						new String[] {AENetworkClassifier.AT_I2P} );
					
					for ( DistributedDatabase ddb: ddbs ){
						
						InetSocketAddress address = InetSocketAddress.createUnresolved( i2p_address, Integer.parseInt( i2p_port ));
						
						
						DistributedDatabaseContact contact = ddb.importContact( address );
						
						DistributedDatabaseKey key = ddb.createKey( new byte[10], "quack" );
						
						DistributedDatabaseValue value = contact.read(
							new DistributedDatabaseProgressListener() {
								
								@Override
								public void reportSize(long size) {
									System.out.println( "size=" + size );
								}
								
								@Override
								public void reportCompleteness(int percent) {
									System.out.println( "done=" + percent );
								}
								
								@Override
								public void reportActivity(String str) {
									System.out.println( "act=" + str );
								}
							},
							test_xfer, 
							key, 
							60*1000 );
						
						System.out.println( "DDBValue=" + ( value == null?"null":((byte[])value.getValue(byte[].class )).length));
					}
					
				}else if ( cmd.equals( "ping_node" )){

					if ( bits.length != 2 ){
						
						throw( new Exception( "usage: ping_node <nid_hash>"));
					}
					
					byte[] hash = decodeHash( bits[1] );
					
					NodeInfo ni = dht.getNodeInfo( hash );
					
					if ( ni == null ){
						
						adapter.log( "Node not found in routing table" );
						
					}else{
						
						dht.ping( ni );
					}
					
				}else if ( cmd.equals( "bootstrap" )){
					
					dht.requestBootstrap();
					
				}else if ( cmd.equals( "boottest" )){
					
					List<NodeInfo> nodes = dht.getNodesForBootstrap(8);
					
					for ( NodeInfo node: nodes ){
						
						adapter.log( "    " + node.toString());
					}
					
				}else if ( cmd.equals( "extboot" )){
					
					adapter.tryExternalBootstrap( dht, true );
					
				}else if ( cmd.equals( "alt_net" )){

					List<DHTTransportAlternativeContact> contacts = DHTUDPUtils.getAlternativeContacts( DHTTransportAlternativeNetwork.AT_I2P, 16 );
					
					System.out.println( "alt_contacts=" + contacts.size());
					
					for ( DHTTransportAlternativeContact c: contacts ){
						
						System.out.println( c.getAge());
						
						System.out.println( "    " + c.getProperties() + " -> " + plugin_maybe_null.alt_network_handler.decodeContact( c ));
					}
				}else if ( cmd.equals( "bridge_put" )){

					if ( bits.length != 3 ){
						
						throw( new Exception( "usage: bridge_put key value"));
					}
					
					byte[] key = bits[1].getBytes( "UTF-8" );
							
					byte[] value = bits[2].getBytes( "UTF-8" );

					plugin_maybe_null.bridgePut( "Bridge Test", key, value, null );
					
				}else{
			
					adapter.log( "Usage: print|info..." );
				}
			}
		}	
	}
		
	@Override
	public List<NodeInfo>
	getAlternativeContacts(
		int				max )
	{
		I2PHelperAltNetHandler anh = alt_network_handler;
		
		List<NodeInfo> result = new ArrayList<NodeInfo>();
		
		if ( anh != null ){
			
			List<DHTTransportAlternativeContact> contacts = DHTUDPUtils.getAlternativeContacts( DHTTransportAlternativeNetwork.AT_I2P, max );

			if ( contacts.size() > 0 ){
										
				for ( DHTTransportAlternativeContact c: contacts ){
				
					NodeInfo ni = anh.decodeContact( c );
					
					if ( ni != null ){	
						
						result.add( ni );
						
						if ( result.size()  >= max ){
							
							break;
						}
					}
				}
			}
		}
		
		return( result );
	}
	
	@Override
	public void
	tryExternalBootstrap(
		I2PHelperDHT	dht,
		boolean			force )
	{
		long	now = SystemTime.getMonotonousTime();
				
		if ( 	force ||
				last_external_bootstrap == 0 || 
				now - last_external_bootstrap >= EXTERNAL_BOOTSTRAP_PERIOD ){
			
			last_external_bootstrap = now;
		
			log( "External bootstrap requested" );
			
			try{
				PluginProxy proxy = AEProxyFactory.getPluginProxy( "I2P bootstrap", new URL( BOOTSTRAP_SERVER ));
			
				if ( proxy != null ){
					
					boolean	worked = false;
					
					try{
						HttpURLConnection url_connection = (HttpURLConnection)proxy.getURL().openConnection( proxy.getProxy());
						
						url_connection.setConnectTimeout( 3*60*1000 );
						url_connection.setReadTimeout( 30*1000 );
				
						url_connection.connect();
				
						try{
							InputStream	is = url_connection.getInputStream();
				
							Map map = BDecoder.decode( new BufferedInputStream( is ));
							
							List<Map> nodes = (List<Map>)map.get( "nodes" );
							
							log( "I2P Bootstrap server returned " + nodes.size() + " nodes" );
							
							last_external_bootstrap_nodes 	= nodes;
							last_external_bootstrap_import	= now;
							
							for ( Map m: nodes ){
								
								NodeInfo ni = dht.heardAbout( m );
								
								if ( ni != null ){
									
									log( "    imported " + ni );
								}
							}
				
						}finally{
				
							url_connection.disconnect();
						}
					}finally{
						
						proxy.setOK( worked );
					}	
				}else{
					
					throw( new Exception( "No plugin proxy available" ));
				}
			}catch( Throwable e ){
			
				log( "External bootstrap failed: " + Debug.getNestedExceptionMessage(e));
				
					// retry if we got a timeout or malformed socks error reply
				
				String msg = Debug.getNestedExceptionMessage(e).toLowerCase();
				
				if ( msg.contains( "timeout" ) || msg.contains( "malformed" )){
					
					// reschedule with a 2 min delay
					
					last_external_bootstrap = now - (EXTERNAL_BOOTSTRAP_PERIOD - 2*60*1000 );
				}
			}
		}else{
			I2PHelperAltNetHandler anh = alt_network_handler;
			
			if ( anh != null ){
				
				List<DHTTransportAlternativeContact> contacts = DHTUDPUtils.getAlternativeContacts( DHTTransportAlternativeNetwork.AT_I2P, 16 );
	
				if ( contacts.size() > 0 ){
					
					int	found = 0;
							
					for ( DHTTransportAlternativeContact c: contacts ){
					
						NodeInfo ni = anh.decodeContact( c );
						
						if ( ni != null ){
						
							dht.heardAbout( ni );
							
							found++;
						}
					}
					
					if ( found > 0 ){
					
						log( "Injecting " + found + " alt-network nodes" );
					}
				}
			}
			
			List<NodeInfo>	temp = null;
			
			synchronized( bootstrap_nodes_from_peers ){
				
				if ( bootstrap_nodes_from_peers.size() > 0 ){
					
					temp = new ArrayList<NodeInfo>( bootstrap_nodes_from_peers );
					
					bootstrap_nodes_from_peers.clear();
				}
			}
			
			if ( temp != null ){
			
				log( "Injecting bootstrap nodes from peers" );
				
				for ( NodeInfo ni: temp ){
					
						// these have no NID yet
					
					dht.ping( ni.getDestination(), ni.getPort(), false );
				}
				
				if ( temp.size() > 5 ){
					
					return;
				}
			}
			
			if ( 	last_external_bootstrap_nodes != null &&
					now - last_external_bootstrap_import >= 3*60*1000 ){
				
				log( "Injecting cached bootstrap nodes" );
				
				last_external_bootstrap_import = now;
				
				for ( Map m: last_external_bootstrap_nodes ){
					
					dht.heardAbout( m );
				}
			}
		}
	}
	
	public String[]
	getNetworks(
		Download	download )
	{
		return( download.getListAttribute( ta_networks ));
	}
	
	public int
	selectDHTIndex()
	{
		return(selectDHTIndex((String[])null ));
	}
	
	public int
	selectDHTIndex(
		byte[]	torrent_hash )
	{
		try{
			Download download = plugin_interface.getDownloadManager().getDownload( torrent_hash );
				
			return( selectDHTIndex( download ));
			
		}catch( Exception e ){
		}
		
		return( selectDHTIndex());
	}
	
	public int
	selectDHTIndex(
		Download	download )
			
	{ 
		if ( download == null ){
		
			return( selectDHTIndex());
			
		}else{
						
			return( selectDHTIndex( getNetworks( download )));
		}
	}
	
	public int
	selectDHTIndex(
		Map<String,Object>		options )
	{
		String[] peer_networks = options==null?null:(String[])options.get( "peer_networks" );
		
		return( selectDHTIndex( peer_networks ));
	}
	
	public int
	selectDHTIndex(
		String[]		peer_networks )
	{		
		if ( dht_count < 2 ){
			
			return( I2PHelperRouter.DHT_MIX );
		}
		
		if ( peer_networks == null || peer_networks.length == 0 ){
			
			return( I2PHelperRouter.DHT_MIX );
		}
		
		for ( String net: peer_networks ){
			
			if ( net == AENetworkClassifier.AT_PUBLIC ){
				
				return( I2PHelperRouter.DHT_MIX );
			}
		}
	
		return( I2PHelperRouter.DHT_NON_MIX );
	}
		
	public I2PHelperDHT
	selectDHT(
		byte[]		torrent_hash )
	{
		I2PHelperRouter router = getRouter();
		
		if ( router != null ){
	
			I2PHelperRouterDHT dht = router.selectDHT( torrent_hash );
			
			if ( dht != null ){
				
				return( dht.getDHT());
			}
		}
		
		return( null );
	}
	
	public void
	handleDHTPort(
		String		host,
		int			port )
	{
		if ( port <= 0 || port >= 65535 ){
			
			return;	// invalid according to NodeInfo
		}
		
		Destination dest;
		
		synchronized( dest_map ){
			
			dest = dest_map.get( host );
		}
		
		if ( dest != null ){
			
			NodeInfo ni = new NodeInfo( dest, port );
			
			synchronized( bootstrap_nodes_from_peers ){
				
				if ( !bootstrap_nodes_from_peers.contains( ni )){
					
					bootstrap_nodes_from_peers.addFirst( ni );
					
					if ( bootstrap_nodes_from_peers.size() > NODES_FROM_PEERS_MAX ){
						
						bootstrap_nodes_from_peers.removeLast();
					}
				}
			}
		}
	}
	
	@Override
	public void 
	outgoingConnection(
		I2PSocket i2p_socket )
		
		throws Exception
	{
		Destination dest = i2p_socket.getPeerDestination();
		
		if ( dest != null ){
			
			byte[]	peer_hash = dest.calculateHash().getData();
			
			String peer_ip = Base32.encode( peer_hash ) + ".b32.i2p";

			synchronized( dest_map ){
				
				dest_map.put( peer_ip, dest );
			}
		}
	}
	
	@Override
	public void 
	incomingConnection(
		I2PHelperRouterDHT		dht,
		I2PSocket 				i2p_socket )
		
		throws Exception 
	{
		forwardSocket( dht, i2p_socket, true, COConfigurationManager.getIntParameter( "TCP.Listen.Port" ));
	}
	
	@Override
	public String 
	lookup(
		String address ) 
	{
		return( hostname_service.lookup( address ));
	}
	
	private static final boolean OLD_STYLE_FORWARD = false;
	
	private void
	forwardSocket(
		I2PHelperRouterDHT			dht,
		I2PSocket 					i2p_socket,
		boolean						handle_maggots,
		int							target_port )
			
		throws Exception
	{		
		Socket bigly_socket;
		
		if ( OLD_STYLE_FORWARD ){
			
			bigly_socket = new Socket( Proxy.NO_PROXY );
			
		}else{
			
			SocketChannel channel = SocketChannel.open();

			bigly_socket = channel.socket();
		}
		
		try{
			Destination dest = i2p_socket.getPeerDestination();
			
			if ( dest == null ){
				
				i2p_socket.close();
				
				return;
			}
			
			byte[]	remote_hash = dest.calculateHash().getData();
			
			String remote_ip = Base32.encode( remote_hash ) + ".b32.i2p";
			
			
			// System.out.println( "Incoming from " + peer_ip + ", port=" + i2p_socket.getLocalPort());
			
			if ( handle_maggots && i2p_socket.getLocalPort() == 80 ){
				
				handleMaggotRequest( i2p_socket, remote_hash );
				
			}else{
			
				
				synchronized( dest_map ){
					
					dest_map.put( remote_ip, dest );
				}
				
				bigly_socket.bind( null );
				
				final int proxy_port = bigly_socket.getLocalPort();
				
				String local_ip;
				
				if ( dht == null ){
				
					byte[]	local_hash = i2p_socket.getThisDestination().calculateHash().getData();
				
					local_ip = Base32.encode( local_hash ) + ".b32.i2p";
					
				}else{
					
					local_ip = dht.getB32Address();
				}
				
					// we need to pass the peer_ip to the core so that it doesn't just see '127.0.0.1'
				
				final AEProxyAddressMapper.PortMapping mapping = 
						AEProxyFactory.getAddressMapper().registerPortMapping( 
							proxy_port, 
							i2p_socket.getLocalPort(),
							local_ip,
							i2p_socket.getPort(),
							remote_ip,
							null );
				
				final Integer dht_index = dht==null?null:dht.getDHTIndex();
				
				if ( dht_index != null ){
					
					synchronized( local_port_map ){
						
						local_port_map.put( proxy_port, dht_index );
					}
				}
				
				// System.out.println( "local port=" + local_port );
				
				boolean	ok = false;
				
				try{
					InetAddress bind = NetworkAdmin.getSingleton().getSingleHomedServiceBindAddress();
					
					if ( bind == null || bind.isAnyLocalAddress()){
						
						bind = InetAddress.getByName( "127.0.0.1" );
					}
					
					bigly_socket.connect( new InetSocketAddress( bind, target_port));
				
					bigly_socket.setTcpNoDelay( true );
					
					Runnable	on_complete = 
						new Runnable()
						{
							private int done_count;
							
							@Override
							public void
							run()
							{
								synchronized( this ){
									
									done_count++;
									
									if ( done_count < 2 ){
										
										return;
									}
								}
								
								mapping.unregister();
								
								if ( dht_index != null ){
									
									synchronized( local_port_map ){
										
										if ( local_port_map.get( proxy_port ) == dht_index ){
										
											local_port_map.remove( proxy_port );
										}
									}
								}
							}
						};
						
					forwardSocket( i2p_socket, bigly_socket, on_complete );
										
					ok = true;
					
				}finally{
					
					if ( !ok ){
						
						mapping.unregister();
						
						if ( dht_index != null ){
							
							synchronized( local_port_map ){
								
								if ( local_port_map.get( proxy_port ) == dht_index ){
								
									local_port_map.remove( proxy_port );
								}
							}
						}
					}
				}
			}
		}catch( Throwable e ){
			
			boolean ignore = false;
			
			if ( e instanceof IOException ){
				
				String message = Debug.getNestedExceptionMessage( e );
				
				if ( message != null ){
					
					message = message.toLowerCase( Locale.US );
				
					if (	message.contains( "closed" ) ||
							message.contains( "aborted" ) ||
							message.contains( "disconnected" ) ||
							message.contains( "reset" ) ||
							message.contains( "end of" )){
			
						ignore = true;
					}
				}
			}
			
			if ( !ignore ){
			
				Debug.out( e );
			}
			
			try{
				i2p_socket.close();
				
			}catch( Throwable f ){		
			}
			
			if ( bigly_socket != null ){
				
				try{
					bigly_socket.close();
					
				}catch( Throwable f ){
				}
			}
		}
	}
	
	private void
	forwardSocket(
		I2PSocket	i2p_socket,
		Socket		bigly_socket,
		Runnable	on_complete )
	
		throws Exception
	{
		//runPipe( i2p_socket.getInputStream(), bigly_socket.getOutputStream(), on_complete );
		
		//runPipe( bigly_socket.getInputStream(), i2p_socket.getOutputStream(), on_complete );
	
		socket_forwarder.forward( i2p_socket, bigly_socket, on_complete );
	}
	
	
	private I2PHelperTracker
	getTracker(
		long		timeout )
	{
		long start = SystemTime.getMonotonousTime();
		
		while( true ){
			
			if ( unloaded ){
				
				break;
			}
			
			if ( tracker != null ){
				
				return( tracker );
			}
			
			if ( SystemTime.getMonotonousTime() - start >= timeout ){
				
				break;
			}
			
			try{
				Thread.sleep(500);
				
			}catch( Throwable e ){
				
				break;
			}
		}
		
		return( null );
	}
	
	private AtomicInteger	active_maggot_requests 	= new AtomicInteger();
	private BloomFilter		maggot_bloom			= null;
	private long			maggot_bloom_create_time;
	
	private void 
	handleMaggotRequest(
		final I2PSocket 	i2p_socket,
		byte[]				peer_hash )
		
		throws Exception 
	{
		BloomFilter	bloom = maggot_bloom;
		
		if ( bloom == null ){
			
			maggot_bloom = bloom = BloomFilterFactory.createAddRemove4Bit( 128 );
			
			maggot_bloom_create_time = SystemTime.getMonotonousTime();
			
		}else{
			
			if ( SystemTime.getMonotonousTime() - maggot_bloom_create_time > 3*60*1000 ){
				
				maggot_bloom = null;
			}
		}
		
		if ( bloom.add( peer_hash ) > 3 ){
			
			throw( new Exception( "Too many maggot requests from " + ByteFormatter.encodeString( peer_hash )));
		}
		
		if ( active_maggot_requests.incrementAndGet() > 3 ){
			
			active_maggot_requests.decrementAndGet();
			
			throw( new Exception( "Too many active maggot requests" ));
		}
		
		new AEThread2( "I2P.maggot" )
		{
			@Override
			public void
			run()
			{
				try{
					InputStream is = i2p_socket.getInputStream();
					
					byte[]	buffer = new byte[1024];
					
					String	header = "";
					
					while( true ){
						
						int len = is.read( buffer );
						
						if ( len <= 0 ){
							
							break;
						}
						
						header += new String( buffer, 0, len, "ISO8859-1" );
						
						if ( header.contains( "\r\n\r\n" )){
							
							String[]	lines = header.split( "\r\n" );
							
							String 	line = lines[0].trim();
							
							if ( line.startsWith( "GET " )){
								
								line = line.substring( 4 ).trim();
								
								int pos = line.lastIndexOf( ' ' );
								
								String url = line.substring( 0, pos ).trim();
								
								pos = url.lastIndexOf( '/' );
									
								if ( pos >= 0 ){
									
									url = url.substring(pos+1);
								}
								
								String[] bits = url.split( ":" );
								
								byte[]	info_hash 		= ByteFormatter.decodeString( bits[0].trim());
								byte[]	torrent_sha1  	= ByteFormatter.decodeString( bits[1].trim());
								
								System.out.println( "Maggot request: " + url );
								
								Download download = plugin_interface.getDownloadManager().getDownload( info_hash );
								
								if ( download != null ){
									
									Torrent torrent = download.getTorrent();
									
									if ( torrent != null && !TorrentUtils.isReallyPrivate( PluginCoreUtils.unwrap( torrent ))){
										
										String torrent_name = torrent.getName() + ".torrent";
										
										byte[] torrent_data = torrent.writeToBEncodedData();
										
											// could check the sha1 sometime...
										
										OutputStream os = i2p_socket.getOutputStream();
										
										os.write(( 
											"HTTP/1.0 200 OK\r\n" + 
											"Content-Length: " + torrent_data.length + "\r\n" +
											"Content-Disposition: attachment; filename=\"" + torrent_name + "\"\r\n" +
											"Content-Description: " + torrent_name + "\r\n" +
											"Content-Type: application/x-bittorrent\r\n" +
											"\r\n").getBytes( "UTF-8" ));
										
										os.write( torrent_data );
										
										os.flush();
									}
								}
							}
							
							break;
						}
						
						if ( header.length() > 8192 ){
							
							throw( new Exception( "Error reading header" ));
						}
					}
					
				}catch( Throwable e ){
					
				}finally{
					
					try{
						try{
							i2p_socket.getOutputStream().close();
							
						}catch( Throwable e ){
						}
						
						i2p_socket.close();
						
					}catch( Throwable e ){
						
					}
					
					active_maggot_requests.decrementAndGet();
				}
			}
		}.start();
	}
	
//	private void
//	runPipe(
//		final InputStream		from,
//		final OutputStream		to,
//		final Runnable			on_complete )
//	{
//		new AEThread2( "I2P.in.pipe" )
//		{
//			@Override
//			public void
//			run()
//			{
//				try{
//					byte[]	buffer = new byte[16*1024];
//					
//					while( !unloaded ){
//					
//						int	len = from.read( buffer );
//						
//						if ( len <= 0 ){
//							
//							break;
//						}
//						
//						to.write( buffer, 0, len );
//					}
//				}catch( Throwable e ){
//					
//				}finally{
//					
//					try{
//						from.close();
//						
//					}catch( Throwable e ){
//					}
//					
//					try{
//						to.close();
//						
//					}catch( Throwable e ){
//					}
//					
//					on_complete.run();
//				}
//			}
//		}.start();
//	}
	
	@Override
	public void
	log(
		String	str )
	{
		if ( log != null ){
			
			log.log( str );
			
		}else{
			
			System.out.println( str );
		}
	}
	
	public void
	log(
		String		str,
		Throwable	e )
	{
		if ( log != null ){
			
			log.log( str, e );
			
		}else{
			
			System.err.println( str );
			
			e.printStackTrace();
		}
	}
	
	private static byte[]
	decodeHash(
		String	hash_str )
	{		
		byte[] hash;
		
		int	pos = hash_str.indexOf( ".b32" );
		
		if ( pos != -1 ){
			
			hash = Base32.decode( hash_str.substring(0,pos));
			
		}else{
			
			if ( hash_str.length() == 40 ){
				
				hash = ByteFormatter.decodeString( hash_str );
				
			}else if ( hash_str.length() == 32 ){
				
				hash = Base32.decode( hash_str );
			}else{
				
				hash =  Base64.decode( hash_str );
			}
		}
		
		return( hash );
	}
	
	private void
	setUnloadable(
		boolean	b )
	{
		PluginInterface pi = plugin_interface;
		
		if ( pi != null ){
			
			pi.getPluginProperties().put( "plugin.unload.disabled", String.valueOf( !b ));
		}
	}
	
	public void
	handleBridgePut(
		I2PHelperDHTPluginInterface		dht,
		byte[]							key,
		String							description,
		byte[]							value,
		byte							flags,
		DHTPluginOperationListener		listener )
	{
		if ( dht.getDHTIndex() != I2PHelperRouter.DHT_NON_MIX ){
			
			Debug.out( "Bridged operations must be performed on non-mix DHT" );
			
			listener.starts( key );
			
			listener.complete( key, false );
			
		}else{
			
			bridgePut( description, key, value, listener );
		}
	}
	
	private void
	bridgePut(
		String						desc,
		byte[]						key,
		byte[]						value,
		DHTPluginOperationListener	listener )
	{
		dht_bridge.writeToBridge( desc, key, value, listener );
	}

		// IPC methods
	
	public Object[]
	getProxy(
		String		reason,
		String		host,
		int			port )
	
		throws IPCException
	{
		return( getProxy( reason, host, port, null ));
	}
	
	public Object[]
	getProxy(
		String					reason,
		String					host,
		int						port,
		Map<String,Object>		proxy_options )
	
		throws IPCException
	{
		if ( !plugin_enabled ){
			
			return( null );
		}
		
		if ( !host.toLowerCase().endsWith( ".i2p" )){
			
			return( null );
		}
		
		if ( proxy_options == null ){
			
			proxy_options = new HashMap<String, Object>();
		}
		
		//System.out.println( "getProxy: " + reason + "/" + host + ":" + port + ", opt=" + getOptionsString( proxy_options ));
		
		synchronized( this ){
			
			if ( unloaded ){
				
				return( null );
			}

			I2PHelperSocksProxy socks_proxy = getSocksProxy();
				
			try{
				int intermediate_port = socks_proxy.getPort();
				
				Map<String,Object>	output = new HashMap<>();
				
				String intermediate_host = socks_proxy.getIntermediateHost( host, proxy_options, output );
		
				Proxy proxy = new Proxy( Proxy.Type.SOCKS, new InetSocketAddress( "127.0.0.1", intermediate_port ));	
								
				proxy_map.put( proxy, new ProxyMapEntry( host, intermediate_host, output ));
		
				//System.out.println( "proxy_map=" + proxy_map.size());
				
				//last_use_time	= SystemTime.getMonotonousTime();
	
				//proxy_request_count.incrementAndGet();
					
				return( new Object[]{ proxy, intermediate_host, port });
				
			}catch( Throwable e ){
				
				throw( new IPCException( e ));
			}
		}
	}
	
	public Object[]
	getProxy(
		String					reason,
		URL						url )
		
		throws IPCException
	{
		return( getProxy( reason, url,null ));
	}
	
	public Object[]
	getProxy(
		String					reason,
		URL						url,
		Map<String,Object>		proxy_options )
		
		throws IPCException
	{
		if ( !plugin_enabled ){
			
			return( null );
		}
		
		String 	host = url.getHost();
				
		if ( !host.toLowerCase().endsWith( ".i2p" )){
			
			return( null );
		}
		
		if ( proxy_options == null ){
			
			proxy_options = new HashMap<String, Object>();
		}
		
		//System.out.println( "getProxy: " + reason + "/" + url + ", opt=" + getOptionsString( proxy_options ));

		
		synchronized( this ){
			
			if ( unloaded ){
				
				return( null );
			}

			I2PHelperSocksProxy socks_proxy = getSocksProxy();
			
			try{
				int intermediate_port = socks_proxy.getPort();
				
				Map<String,Object>	output = new HashMap<>();

				String intermediate_host = socks_proxy.getIntermediateHost( host, proxy_options, output );
		
				Proxy proxy = new Proxy( Proxy.Type.SOCKS, new InetSocketAddress( "127.0.0.1", intermediate_port ));	
								
				proxy_map.put( proxy, new ProxyMapEntry( host, intermediate_host, output ));
		
				//System.out.println( "proxy_map=" + proxy_map.size());
	
				//last_use_time	= SystemTime.getMonotonousTime();
	
				//proxy_request_count.incrementAndGet();
					
				url = UrlUtils.setHost( url, intermediate_host );
	
				return( new Object[]{ proxy, url, host });
				
			}catch( Throwable e ){
				
				throw( new IPCException( e ));
			}
		}
	}
	
	public void
	setProxyStatus(
		Proxy		proxy,
		boolean		good )
	{
		ProxyMapEntry	entry;
		
		synchronized( this ){
			
			entry = proxy_map.remove( proxy );
		
			if ( entry != null ){
					
				// System.out.println( "proxy_map=" + proxy_map.size());

				// String 	host 				= entry.getHost();
				
				/*
				if ( good ){
					
					proxy_request_ok.incrementAndGet();
					
				}else{
					
					proxy_request_failed.incrementAndGet();
				}
				
				updateProxyHistory( host, good );
				*/
				
				String	intermediate	= entry.getIntermediateHost();
	
				if ( intermediate != null ){
					
					if ( socks_proxy != null ){
						
						socks_proxy.removeIntermediateHost( intermediate );
					}
				}
			}else{
			
				Debug.out( "Proxy entry missing!" );
			}
		}
	}
	
	public Map<String,Object>
	getProxyStatus(
		Proxy		proxy )
	{
		synchronized( this ){
			
			ProxyMapEntry entry = proxy_map.get( proxy );
		
			if ( entry != null ){
				
				return( entry.output );
			}
		}
		
		return( null );
	}
	
	public Map<String,Object>
	getProxyServer(
		String				reason,
		Map<String,Object>	server_options )
		
		throws IPCException
	{
		if ( !plugin_enabled ){
			
			return( null );
		}
		
		try{
			String	server_id = (String)server_options.get( "id" );
			
			int	target_port = (Integer)server_options.get( "port" );
			
			long	start = SystemTime.getMonotonousTime();
			
			while( true ){
				
				if ( unloaded ){
					
					return( null );
				}
				
				if ( SystemTime.getMonotonousTime() - start > 2*60*1000 ){
					
					throw( new IPCException( "Timeout waiting for router startup" ));
				}
				
				I2PHelperRouter current_router = router;
				
				if ( current_router == null ){
					
					try{
						Thread.sleep(1000);
						
						
					}catch( Throwable e ){
					
						Debug.out( e );
						
						return( null );
					}
				}else{
					
					I2PHelperRouter.ServerInstance server = 
						current_router.createServer(
							server_id, 
							false,
							I2PHelperRouter.SM_TYPE_OTHER,
							new I2PHelperRouter.ServerAdapter() 
							{			
								@Override
								public void 
								incomingConnection(
									I2PHelperRouter.ServerInstance		server,
									I2PSocket 							i2p_socket )
											
									throws Exception 
								{	
									int port = (Integer)server.getUserProperty( "port" );
									
									forwardSocket( null, i2p_socket, false, port );
								}
							});
					
						// update the port if we've alreready got a server and this is just an update
					
					server.setUserProperty( "port", target_port );
					
					Map<String,Object>	reply = new HashMap<String, Object>();
					
					reply.put( "host", server.getB32Dest());
					
					return( reply );
				}
			}
		}catch( Throwable e ){
			
			throw( new IPCException( e ));
		}
	}
	
	private Map<Integer,I2PHelperDHTPluginInterface>	dht_pi_map 		= new HashMap<Integer, I2PHelperDHTPluginInterface>();
	private Map<String,I2PHelperDHTPluginInterface>		dht_client_map 	= new HashMap<String, I2PHelperDHTPluginInterface>();
	
	public List<I2PHelperDHTPluginInterface>
	getProxyDHTs()
	{
		synchronized( dht_client_map ){

			return( new ArrayList<I2PHelperDHTPluginInterface>( dht_client_map.values()));
		}
	}
	
	public DHTPluginInterface
	getProxyDHT(
		String				reason,
		Map<String,Object>	server_options )
		
		throws IPCException
	{
		if ( !plugin_enabled ){
			
			return( null );
		}
				
			// we hand this out then we have state that needs to persist as long
			// as Vuze is up
		
		setUnloadable( false );
		
		try{		
			int		dht_index = -1;
			
			Download download = (Download)server_options.get( "download" );
			
			if ( download == null ){
			
				String[]	networks = (String[])server_options.get( "networks" );
				
				if ( networks != null ){
					
					dht_index = selectDHTIndex( networks );				
				}
				
				String server_id = (String)server_options.get( "server_id");
					
				if ( server_id != null ){
						
					if ( dht_index == -1 ){
						
							// server default is non-mix
						
						dht_index = I2PHelperRouter.DHT_NON_MIX;
					}

					Boolean b_is_transient = (Boolean)server_options.get( "server_id_transient" );
					
					boolean is_transient = b_is_transient != null && b_is_transient;
					
					long	start = SystemTime.getMonotonousTime();

					while( true ){
						
						if ( unloaded ){
							
							return( null );
						}
						
						if ( SystemTime.getMonotonousTime() - start > 2*60*1000 ){
							
							throw( new IPCException( "Timeout waiting for router startup" ));
						}
						
						I2PHelperRouter current_router = router;
						
						if ( current_router == null ){
							
							try{
								Thread.sleep(1000);									
								
							}catch( Throwable e ){
							
								Debug.out( e );
								
								return( null );
							}
						}else{
							
							int sm_type = I2PHelperRouter.SM_TYPE_OTHER;
							
							Number i_sm_type = (Number)server_options.get( "server_sm_type");
							
							if ( i_sm_type != null ){
								
								sm_type = i_sm_type.intValue();
							}
							
							I2PHelperRouter.ServerInstance server = 
								current_router.createServer(
									server_id, 
									is_transient,
									sm_type,
									new I2PHelperRouter.ServerAdapter() 
									{			
										@Override
										public void 
										incomingConnection(
											I2PHelperRouter.ServerInstance		server,
											I2PSocket 							i2p_socket )
													
											throws Exception 
										{	
											i2p_socket.close();
										}
									});
							
							
							I2PHelperDHTPluginInterface pi;
							
							synchronized( dht_pi_map ){
								
								pi = dht_pi_map.get( dht_index );
								
								if ( pi == null ){
									
									pi = new I2PHelperDHTPluginInterface( this, dht_index );
									
									dht_pi_map.put( dht_index, pi );
								}
							}
							
							Number timeout = (Number)server_options.get( "timeout" );
							
							I2PHelperAZDHT dht = pi.getDHT( timeout == null?2*60*1000:timeout.intValue());
							
							if ( dht == null ){
								
								throw( new IPCException( "Timeout waiting for DHT initialisation" ));
							}
							
							synchronized( dht_client_map ){
								
								String client_key = server_id + "/" + dht_index;
								
								I2PHelperDHTPluginInterface client_pi = dht_client_map.get( client_key );
								
								if ( client_pi == null ){
									
									DHTAZClient client = new DHTAZClient( server, dht, 10000 + (dht_index*100), I2PHelperPlugin.this );
									
									String server_name = (String)server_options.get( "server_name");

									if ( server_name == null ){
										
										server_name = client_key;
									}
									
									client_pi = new I2PHelperDHTPluginInterface( this, dht_index, client, server_name );

									dht_client_map.put( client_key, client_pi );
								}
								
								return( client_pi );
							}
						}
					}
				}else{
					if ( dht_index == -1 ){
					
							// general default is mix
						
						dht_index = I2PHelperRouter.DHT_MIX;
					}
				}
			}else{
			
				dht_index = selectDHTIndex( download );
			}
			
			synchronized( dht_pi_map ){
				
				I2PHelperDHTPluginInterface pi = dht_pi_map.get( dht_index );
				
				if ( pi == null ){
					
					pi = new I2PHelperDHTPluginInterface( this, dht_index);
					
					dht_pi_map.put( dht_index, pi );
				}
				
				return( pi );
			}
			
		}catch( IPCException e ){
			
			throw( e );
			
		}catch( Throwable e ){
			
			throw( new IPCException( e ));
		}
	}
	
	public void
	lookupTorrent(
		final String				reason,
		final byte[]				torrent_hash,
		final Map<String,Object>	options,
		final IPCInterface			callback )
		
		throws IPCException
	{
		if ( unloaded || !plugin_enabled || lookup_dispatcher.getQueueSize() > 32 ){
			
			reportLookupStatus( 
				callback, TrackerPeerSource.ST_UNAVAILABLE, 
				"Plugin unavailable, disabled or overloaded" );
			
			return;
		}
		
		reportLookupStatus( callback, TrackerPeerSource.ST_INITIALISING );

		lookup_dispatcher.dispatch(
			new AERunnable() {
				
				@Override
				public void 
				runSupport() 
				{
					try{
						I2PDHTTrackerPlugin tracker_plugin = null;
						
						long	start = SystemTime.getMonotonousTime();
						
						String dots = "";
						
						while( true ){
							
							I2PHelperTracker t = tracker;
							
							if ( t != null ){
								
								tracker_plugin = t.getTrackerPlugin();
								
								if ( tracker_plugin != null ){
									
									break;
								}
							}
							
							dots += ".";
							
							reportLookupStatus( callback, "Waiting for tracker initialization" + dots);

							if ( dots.length() == 3 ){	
								
								dots = "";
							}
							
							router_init_sem.reserve(2500);
							
							if ( unloaded || !plugin_enabled || ( SystemTime.getMonotonousTime() - start > 2*60*1000 )){
								
								reportLookupStatus( 
									callback, TrackerPeerSource.ST_UNAVAILABLE, 
									"Timeout waiting for tracker, try again later" );
	
								return;
							}
						}


						I2PHelperDHTAdapter adapter = 
							new I2PHelperDHTAdapter()
							{
								private volatile int	leechers	= 0;
								private volatile int 	seeds		= 0;
								private volatile int 	peers		= 0;
								
								private Set<String>	hosts	= new HashSet<String>();
								
								@Override
								public void
								valueRead(
									DHTTransportContactI2P		contact,
									String						host,
									int							contact_state )
								{
									synchronized( hosts ){
										
										if ( hosts.contains( host )){
											
											return;
										}
										
										hosts.add( host );
									}
									
									if ( contact_state == CS_SEED ){
																							
										seeds++;
										
									}else if ( contact_state == CS_LEECH ){
																							
										leechers++;
										
									}else{
																							
										peers++;
									}
									
									try{
										callback.invoke(
											"peerFound",
											new Object[]{
												host, contact_state
											});
										
									}catch( Throwable e){
										
									}
									
									reportLookupStatus( callback, TrackerPeerSource.ST_UPDATING, seeds, leechers, peers );
								}
	
								@Override
								public void
								complete(
									boolean		timeout )
								{
									reportLookupStatus( callback, TrackerPeerSource.ST_ONLINE, seeds, leechers, peers );
								}
							};	
			
						if ( options.containsKey( "server_id" )){
							
							DHTPluginInterface dht_pi = getProxyDHT( "torrentLookup", options );
							
							if ( dht_pi instanceof I2PHelperDHTPluginInterface ){
								
								I2PHelperAZDHT az_dht = ((I2PHelperDHTPluginInterface)dht_pi).getDHT(10*1000);
								
								if ( az_dht != null ){
									
									start = SystemTime.getMonotonousTime();
									
									dots = "";
									
									while( true ){
										
										dots += ".";
										
										reportLookupStatus( callback, "Waiting for DHT initialization" + dots);

										if ( dots.length() == 3 ){	
											
											dots = "";
										}
										
										az_dht.waitForInitialisation( 2500 );
																			
										if ( az_dht.isInitialised()){
											
											break;
										}

										if ( unloaded || !plugin_enabled || ( SystemTime.getMonotonousTime() - start > 2*60*1000 )){
											
											reportLookupStatus( 
												callback, TrackerPeerSource.ST_UNAVAILABLE, 
												"Timeout waiting for DHT, try again later" );
				
											return;
										}
									}
									
									reportLookupStatus( callback, TrackerPeerSource.ST_UPDATING, 0, 0, 0 );
									
									tracker_plugin.trackerGet( 
										reason,
										torrent_hash,
										options,
										az_dht,
										adapter );
								}else{
									
									reportLookupStatus( 
										callback, TrackerPeerSource.ST_UNAVAILABLE , 
										"AZ DHT unavailable, try again later" );
								}
							}else{
								
								reportLookupStatus( 
									callback, 
									TrackerPeerSource.ST_UNAVAILABLE, 
									"DHT not found" );
							}
						}else{
							
							reportLookupStatus( callback, TrackerPeerSource.ST_UPDATING );
	
							tracker_plugin.trackerGet( 
								reason,
								torrent_hash,
								options,
								adapter );
						}
					}catch( Throwable e ){
						
						reportLookupStatus( 
							callback, 
							TrackerPeerSource.ST_UNAVAILABLE,
							"Error: " + Debug.getNestedExceptionMessage(e));
					}
				}
			});
	}
	
	private AsyncDispatcher		manual_tracker_dispatcher = new AsyncDispatcher();
	
	public Map<String,Object>
	addDownloadToTracker(
		final Download				download,
		final Map<String,Object>	options )
		
		throws IPCException
	{
		setUnloadable( false );
		
		if ( manual_tracker_dispatcher.getQueueSize() > 1024 ){
			
			 throw( new IPCException( "Queue to long" ));
		}
		
		manual_tracker_dispatcher.dispatch(
			new AERunnable() {
				
				@Override
				public void 
				runSupport() 
				{
					handleManualTrackerAction( download, options, true );
				}
			});
		
		return( null );
	}
	
	public Map<String,Object>
	removeDownloadFromTracker(
		final Download				download,
		final Map<String,Object>	options )
		
		throws IPCException
	{
		if ( manual_tracker_dispatcher.getQueueSize() > 1024 ){
			
			 throw( new IPCException( "Queue to long" ));
		}
		
		manual_tracker_dispatcher.dispatch(
				new AERunnable() {
					
					@Override
					public void 
					runSupport() 
					{
						handleManualTrackerAction( download, options, false );
					}
				});	
		
		return( null );
	}
	
	private void
	handleManualTrackerAction(
		Download			download,
		Map<String,Object>	options,
		boolean				is_add )
	{
		if ( !plugin_enabled ){
			
			return;
		}
		
		I2PDHTTrackerPlugin	tracker_plugin = null;
	
		while( true ){
			
			I2PHelperTracker t = tracker;
			
			if ( t != null ){
				
				tracker_plugin = t.getTrackerPlugin();
				
				if ( tracker_plugin != null ){
					
					break;
				}
			}
			
			try{
				
				Thread.sleep(1000);
				
			}catch( Throwable e ){
			}
		}
		
		if ( is_add ){
			
			tracker_plugin.downloadAdded(download);
			
		}else{
			
			tracker_plugin.downloadRemoved(download);
		}
	}
	
	private void
	reportLookupStatus(
		IPCInterface		callback,
		int					status,
		String				msg )
	{
		reportLookupStatus( callback, msg );
		
		reportLookupStatus( callback, status );
	}
	
	private void
	reportLookupStatus(
		IPCInterface		callback,
		int					status )
	{
		try{
			callback.invoke(
				"statusUpdate",
				new Object[]{
					status
				});
			
		}catch( Throwable e){
		}	
	}
	
	private void
	reportLookupStatus(
		IPCInterface		callback,
		String				status )
	{
		try{
			callback.invoke(
				"msgUpdate",
				new Object[]{
					status
				});
			
		}catch( Throwable e){
		}	
	}
	
	private void
	reportLookupStatus(
		IPCInterface		callback,
		int					status,
		int					seeds,
		int					leechers,
		int					peers )
	{
		try{
			callback.invoke(
				"statusUpdate",
				new Object[]{
					status, seeds, leechers, peers
				});
			
		}catch( Throwable e){
			
		}
	}
		
		// End IPC
	
	@Override
	public void
	unload()
	{
		unload( false );
	}
	
	public void
	unload(
		boolean	for_closedown )
	{
		synchronized( this ){
			
			unloaded = true;
		}
		
		try{
			writePluginInfo();
			
			if ( router != null ){
				
				router.destroy();
				
				router = null;
			}
			
			if ( socks_proxy != null ){
				
				socks_proxy.destroy();
				
				socks_proxy = null;
			}
			
			if ( !for_closedown ){
				
				if ( config_model != null ){
					
					config_model.destroy();
					
					config_model = null;
				}
				
				if ( view_model != null ){
					
					view_model.destroy();
					
					view_model = null;
				}
				
				if ( ui_view != null ){
					
					ui_view.unload();
				}
				
				if ( tracker != null ){
					
					tracker.destroy();
				}
				
				if ( magnet_handler != null ){
				
					MagnetURIHandler uri_handler = MagnetURIHandler.getSingleton();
				
					uri_handler.removeListener( magnet_handler );
				}
				
				if ( alt_network_handler != null ){
					
					alt_network_handler.destroy();
					
					alt_network_handler = null;
				}
				
				if ( message_handler != null ){
					
					message_handler.destroy();
					
					message_handler = null;
				}
				
				if ( network_mixer != null ){
					
					network_mixer.destroy();
					
					network_mixer = null;
				}
				
				if ( timer_event != null ){
					
					timer_event.cancel();
					
					timer_event = null;
				}
				
				if ( socket_forwarder != null ){
					
					socket_forwarder.destroy();
					
					socket_forwarder = null;
				}
			}
		}finally{
			
			if ( lock_stream != null ){
				
				try{
					lock_stream.close();
					
				}catch( Throwable e ){
				}
				
				lock_stream = null;
			}
			
			if ( lock_file != null ){
				
				lock_file.delete();
				
				lock_file = null;
			}
		}
	}
	
	protected boolean
	checkMixState(
		InetSocketAddress		source,
		byte[]					hash )
	{
		try{
			Download download = plugin_interface.getDownloadManager().getDownload( hash );
			
			if ( !source.isUnresolved()){
				
				int required_dht = selectDHTIndex( download );
				
				synchronized( local_port_map ){
					
					Integer dht_index = local_port_map.get( source.getPort());
					
					if ( dht_index == null || dht_index != required_dht ){
						
						return( false );
					}
				}
			}
			
			I2PHelperNetworkMixer	mixer = network_mixer;
			
			if ( mixer != null ){
								
				if ( download != null ){
					
					mixer.checkMixState( download );
				}
			}
		
			return( true );
			
		}catch( Throwable e ){
		
			return( true );
		}
	}
	
//	private String
//	getOptionsString(
//		Map<String,Object>	opts )
//	{
//		String[] nets = (String[])opts.get( "peer_networks" );
//		
//		if ( nets != null ){
//			
//			String str = "";
//			
//			for ( String net: nets ){
//				
//				str += (str.length()==0?"":",") + net;
//			}
//			
//			return( "peer_networks=" + str );
//		}
//		
//		return( "" );
//	}

	private boolean
	testPort(
		int		port )
	{
		ServerSocketChannel ssc = null;

		try{	
			ssc = ServerSocketChannel.open();
			
			ssc.socket().bind( new InetSocketAddress( "127.0.0.1", port ));
			
			return( true );
			
		}catch( Throwable e ){
			
		}finally{
			
			if ( ssc != null ){
				
				try{
					ssc.close();
					
				}catch( Throwable e ){
					
				}
			}
		}
		
		return( false );
	}
	
	private int
	allocatePort(
		int				def,
		int				exclude_port )
	{
		for ( int i=0;i<32;i++){
			
			int port = 20000 + RandomUtils.nextInt( 20000 );
			
			if ( port == exclude_port ){
				
				continue;
			}
			
			ServerSocketChannel ssc = null;

			try{	
				ssc = ServerSocketChannel.open();
				
				ssc.socket().bind( new InetSocketAddress( "127.0.0.1", port ));
				
				return( port );
				
			}catch( Throwable e ){
				
			}finally{
				
				if ( ssc != null ){
					
					try{
						ssc.close();
						
					}catch( Throwable e ){
						
					}
				}
			}
		}
		
		return( def );
	}
	
	private class
	ProxyMapEntry
	{
		private long	created = SystemTime.getMonotonousTime();
		
		private	String				host;
		private String				intermediate_host;
		private Map<String,Object>	output;

		private
		ProxyMapEntry(
			String				_host,
			String				_intermediate_host,
			Map<String,Object>	_output )
		{
			host				= _host;
			intermediate_host	= _intermediate_host;
			output				= _output;
		}
		
		private String
		getIntermediateHost()
		{
			return( intermediate_host );
		}
	}
	
	public static void
	main(
		String[]	args )
	{
		if ( args.length == 0 ){
			
			System.out.println( "Usage: config_dir");
			
			return;
		}
		
		File config_dir = new File( args[0] );
		
		if ( !config_dir.isDirectory()){
			
			if ( !config_dir.mkdirs()){
				
				System.out.println( "Failed to make directory '" + config_dir + "'" );
				
				return;
			}
		}
		
		boolean bootstrap 	= args.length > 1 && args[1].equals( "true" );
				
		if ( bootstrap ){
			
			System.out.println( "Bootstrap Node" );
		}
		
		boolean vuze_dht 	= args.length > 2 && args[2].equals( "true" );
		
		if ( vuze_dht ){
			
			System.out.println( "Vuze DHT" );
		}
		
		final I2PHelperRouter[] f_router = { null };
		
		I2PHelperAdapter adapter = 
			new I2PHelperAdapter() 
			{
				@Override
				public void
				log(
					String str ) 
				{
					System.out.println( str );
				}
				
				@Override
				public String
				getMessageText(
					String		key )
				{
					return( "!" + key + "!" );
				}
				
				@Override
				public String
				getMessageText(
					String		key,
					String...	args )
				{
					return( "!" + key + "!: " + args );
				}
				
				@Override
				public void 
				stateChanged(
					I2PHelperRouterDHT 	dht,
					boolean				init_done )
				{
				}
				
				@Override
				public List<NodeInfo> 
				getAlternativeContacts(int max) 
				{
					return( new ArrayList<NodeInfo>());
				}
				
				@Override
				public void
				tryExternalBootstrap(
					I2PHelperDHT		dht,
					boolean				force )
				{
					log( "External bootstrap test" );
					
					try{
						URL url = new URL( BOOTSTRAP_SERVER );
						
						URLConnection connection = url.openConnection();
						
						connection.connect();
						
						InputStream is = connection.getInputStream();
						
						Map map = BDecoder.decode( new BufferedInputStream( is ));
									
						List<Map> nodes = (List<Map>)map.get( "nodes" );
						
						log( "I2P Bootstrap server returned " + nodes.size() + " nodes" );
						
						for ( Map m: nodes ){
							
							NodeInfo ni = dht.heardAbout( m );
							
							if ( ni != null ){
								
								log( "    imported " + ni );
							}
						}
				
					}catch( Throwable e ){
						
						log( "External bootstrap failed: " + Debug.getNestedExceptionMessage(e));
					}
				}
				
				@Override
				public void 
				contactAlive(
					I2PHelperDHT 			dht,
					DHTTransportContactI2P 	contact ) 
				{
				}
				
				@Override
				public void
				incomingConnection(
					I2PHelperRouterDHT	dht,
					I2PSocket 			socket )
					
					throws Exception 
				{
					log( "incoming connections not supported" );
					
					socket.close();
				}
				
				@Override
				public void
				outgoingConnection(
					I2PSocket socket )
				{	
				}
				
				@Override
				public PluginInterface
				getPluginInterface()
				{
					return( null );
				}
				
				@Override
				public boolean 
				isDHTEnabled()
				{
					return true;
				}
				
				@Override
				public String 
				lookup(
					String address ) 
				{
					return( null );
				}
			};
			
		try{
			I2PHelperRouter router = f_router[0] = new I2PHelperRouter( null, config_dir, new HashMap<String, Object>(), bootstrap, vuze_dht, false, 1, adapter );
			
				// 19817 must be used for bootstrap node
			
			router.initialiseRouter( 17654, bootstrap?19817:28513 );
			//router.initialiseRouter( "192.168.1.5", 7654 );

			router.initialiseDHTs();
			
			I2PHelperDHT dht = router.selectDHT().getDHT();
			
			I2PHelperTracker tracker = new I2PHelperTracker( adapter, router, null );
			
			I2PHelperConsole console = new I2PHelperConsole();
			
			//I2PHelperSocksProxy	socks_proxy = new I2PHelperSocksProxy( router, 8964, adapter );
			
			I2PHelperBootstrapServer bootstrap_server = null;
			
			if ( bootstrap ){
				
				bootstrap_server = new I2PHelperBootstrapServer( 60000, router );
			}
			
			System.out.println( "Accepting commands" );
			
			while( true ){
				
				String line = console.readLine( ">" );
				
				if ( line == null ){
					
					break;
				}
				
				try{
					line = line.trim();
					
					if ( line.equals( "quit" )){
						
						break;
						
					}else if ( line.equals( "extboot" )){
						
						adapter.tryExternalBootstrap( dht, true );
						
					}else if ( line.equals( "createserver" )){
						
						try{
							router.createServer( 
								"test", 
								false,
								I2PHelperRouter.SM_TYPE_OTHER,
								new I2PHelperRouter.ServerAdapter() 
								{
								
								@Override
								public void 
								incomingConnection(
									I2PHelperRouter.ServerInstance		server,
									I2PSocket 							socket ) 
											
									throws Exception 
								{
									try{
										System.out.println( "got test server connection" );
										
										socket.close();
										
									}catch( Throwable e ){
										
									}
								}
							});
							
						}catch( Throwable e ){
							
							e.printStackTrace();
						}

					}else{
						
						executeCommand( line, router, tracker, adapter, null );
					}
					
					if ( bootstrap_server != null ){
						
						System.out.println( "Bootstrap http: " + bootstrap_server.getString());
					}
				}catch( Throwable e ){
					
					e.printStackTrace();
				}
			}
			
			router.destroy();
			
		}catch( Throwable e ){
			
			e.printStackTrace();
		}
	}
	
	protected static class
	DDBTestXFer
		implements DistributedDatabaseTransferType
	{	
	}
}
