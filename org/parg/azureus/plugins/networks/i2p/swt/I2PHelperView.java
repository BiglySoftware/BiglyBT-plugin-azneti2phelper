/*
 * Created on Jan 30, 2008
 * Created by Paul Gardner
 * 
 * Copyright 2008 Vuze, Inc.  All rights reserved.
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



package org.parg.azureus.plugins.networks.i2p.swt;



import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.biglybt.ui.mdi.MultipleDocumentInterface;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;
import com.biglybt.core.util.TimerEventPeriodic;
import com.biglybt.pif.ui.Graphic;
import com.biglybt.pif.ui.UIInstance;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.ui.config.Parameter;
import com.biglybt.pif.ui.config.ParameterListener;
import com.biglybt.pif.ui.menus.MenuItem;
import com.biglybt.pif.ui.menus.MenuItemListener;
import com.biglybt.pif.ui.menus.MenuManager;
import com.biglybt.ui.swt.Utils;
import com.biglybt.ui.swt.pif.UISWTInstance;
import com.biglybt.ui.swt.pif.UISWTStatusEntry;
import com.biglybt.ui.swt.pif.UISWTStatusEntryListener;
import com.biglybt.ui.swt.pif.UISWTView;
import com.biglybt.ui.swt.pif.UISWTViewEvent;
import com.biglybt.ui.swt.pif.UISWTViewEventListener;
import com.biglybt.ui.swt.views.stats.*;
import org.parg.azureus.plugins.networks.i2p.I2PHelperDHT;
import org.parg.azureus.plugins.networks.i2p.I2PHelperPlugin;
import org.parg.azureus.plugins.networks.i2p.plugindht.I2PHelperDHTPluginInterface;
import org.parg.azureus.plugins.networks.i2p.router.I2PHelperRouter;
import org.parg.azureus.plugins.networks.i2p.vuzedht.DHTI2P;
import org.parg.azureus.plugins.networks.i2p.vuzedht.I2PHelperAZDHT;

import com.biglybt.core.dht.DHT;
import com.biglybt.ui.UIFunctions;
import com.biglybt.ui.UIFunctionsManager;

public class 
I2PHelperView 
	implements UISWTViewEventListener
{
	private static final String	resource_path = "org/parg/azureus/plugins/networks/i2p/swt/";
	
	private I2PHelperPlugin		plugin;
	private UISWTInstance		ui;
	private String				view_id;
	

	private TimerEventPeriodic	status_timer;
	
	private UISWTStatusEntry	status_icon;

	private Image				img_sb_enabled;
	private Image				img_sb_disabled;
	
	private Map<UISWTView,ViewInstance>		views = new HashMap<>();

	private boolean		unloaded;
	
	public
	I2PHelperView(
		I2PHelperPlugin				_plugin,
		UIInstance					_ui,
		String						_view_id,
		final BooleanParameter		icon_enable )
	{
		plugin	= _plugin;
		ui 		= (UISWTInstance)_ui;
		view_id	= _view_id;
				
		ui.addView( UISWTInstance.VIEW_MAIN, view_id, this );
		
		Utils.execSWTThread(
			new Runnable()
			{
				@Override
				public void
				run()
				{
					synchronized( I2PHelperView.this ){
						
						if ( unloaded ){
							
							return;
						}
						
						status_icon	= ui.createStatusEntry();
						
						status_icon.setImageEnabled( true );
						
						status_icon.setVisible( true );
																	
						UISWTStatusEntryListener status_listener = 
							new UISWTStatusEntryListener()
							{
								@Override
								public void
								entryClicked(
									UISWTStatusEntry entry )
								{
									ui.openView( UISWTInstance.VIEW_MAIN, view_id, null );
								}
							};
							
						status_icon.setListener( status_listener );
							
						img_sb_disabled	 	= loadImage( ui, "sb_i2p_disabled.png" );
						img_sb_enabled	 	= loadImage( ui, "sb_i2p_running.png" );
						
						boolean	enabled = plugin.isEnabled();
						
						status_icon.setImage( enabled?img_sb_enabled:img_sb_disabled);
						
						MenuManager menu_manager = plugin.getPluginInterface().getUIManager().getMenuManager();
						
						boolean	is_visible = icon_enable.getValue();
						
						status_icon.setVisible( is_visible );
						
						final MenuItem mi_show =
								menu_manager.addMenuItem(
									status_icon.getMenuContext(),
									"azi2phelper.ui.show.icon" );
							
						mi_show.setStyle( MenuItem.STYLE_CHECK );
						mi_show.setData( new Boolean( is_visible ));
						
						mi_show.addListener(
								new MenuItemListener()
								{
									@Override
									public void
									selected(
										MenuItem			menu,
										Object 				target )
									{
										icon_enable.setValue( false );
									}
								});
						
						icon_enable.addListener(
								new ParameterListener()
								{
									@Override
									public void
									parameterChanged(
										Parameter param )
									{
										boolean is_visible = icon_enable.getValue();
										
										status_icon.setVisible( is_visible );
										
										mi_show.setData( new Boolean( is_visible ));
									}
								});
						
						
						MenuItem mi_sep = menu_manager.addMenuItem( status_icon.getMenuContext(), "" );

						mi_sep.setStyle( MenuItem.STYLE_SEPARATOR );
							
						MenuItem mi_options =
								menu_manager.addMenuItem(
										status_icon.getMenuContext(),
										"MainWindow.menu.view.configuration" );

						mi_options.addListener(
							new MenuItemListener()
							{
								@Override
								public void
								selected(
									MenuItem			menu,
									Object 				target )
								{
									UIFunctions uif = UIFunctionsManager.getUIFunctions();

									if ( uif != null ){

												uif.getMDI().showEntryByID(
														MultipleDocumentInterface.SIDEBAR_SECTION_CONFIG,
														"azi2phelper.name");
											}
								}
							});

						status_icon.setTooltipText( plugin.getStatusText());

						if ( enabled ){
							
							status_timer = SimpleTimer.addPeriodicEvent(
									"I2PView:status",
									10*1000,
									new TimerEventPerformer() 
									{
										@Override
										public void
										perform(
											TimerEvent event ) 
										{
											if ( unloaded ){
												
												TimerEventPeriodic timer = status_timer;
												
												if ( timer != null ){
													
													timer.cancel();
												}
												
												return;
											}
											
											status_icon.setTooltipText( plugin.getStatusText());
										}
									});
						}
					}
				}
			});
	}
	
	protected Image
	loadImage(
		UISWTInstance	swt,
		String			name )
	{
		Image	image = swt.loadImage( resource_path + name );

		return( image );
	}
	
	protected Graphic
	loadGraphic(
		UISWTInstance	swt,
		String			name )
	{
		Image	image = swt.loadImage( resource_path + name );

		Graphic graphic = swt.createGraphic(image );
				
		return( graphic );
	}
		

	@Override
	public boolean
	eventOccurred(
		UISWTViewEvent event )
	{
		UISWTView view = event.getView();
		
		ViewInstance instance;
		
		synchronized( views ){
			
			instance = views.get( view );
		}
		
		switch( event.getType() ){

			case UISWTViewEvent.TYPE_CREATE:{
				
				if ( instance != null ){
					
					return( false );
				}
				
				instance = new ViewInstance( plugin, view );
				
				synchronized( views ){
					
					if ( views.containsKey( view )){
						
						return( false );
					}
					
					views.put( view, instance );
				}
				
				break;
			}
			case UISWTViewEvent.TYPE_INITIALIZE:{
				
				if ( instance != null ){
				
					instance.initialise((Composite)event.getData());
				}
				
				break;
			}
			case UISWTViewEvent.TYPE_REFRESH:{
				
				if ( instance != null ){
					
					instance.refresh( event );
				}
				
				break;
			}
			case UISWTViewEvent.TYPE_DESTROY:{
				
				if ( instance != null ){
					
					instance.destroy();
				}
				
				break;
			}
		}
		
		return true;
	}
	
	public void
	unload()
	{
		synchronized( this ){
			
			unloaded = true;
		}
		
		ui.removeViews( UISWTInstance.VIEW_MAIN, view_id );
		
		List<ViewInstance>	to_destroy;
		
		synchronized( views ){
			
			to_destroy = new ArrayList<>( views.values());
		}
		
		for ( ViewInstance view: to_destroy ){
			
			view.destroy();
		}
		
		if ( status_timer != null ){
			
			status_timer.cancel();
			
			status_timer = null;
		}
		
		if ( status_icon != null ){
			
			status_icon.destroy();
			
			status_icon = null;
		}
		
		Utils.execSWTThread(
			new Runnable()
			{
				@Override
				public void
				run()
				{
					if ( img_sb_disabled != null ){
						
						img_sb_disabled.dispose();
						
						img_sb_disabled = null;
					}
					
					if ( img_sb_enabled != null ){
						
						img_sb_enabled.dispose();
						
						img_sb_enabled = null;
					}
				}
			});
		
	}
	
	private static class
	ViewInstance
	{
		private final I2PHelperPlugin		plugin;
		private final UISWTView				view;
		
		private Composite	composite;

		private int					normal_dht_count;
		private int					proxy_dht_count;
		
		private List<I2PHelperDHTPluginInterface> proxy_dhts;
		
		private DHTView[] 			dht_views;
		private DHTOpsView[]		ops_views;
		
		private TimerEventPeriodic	view_timer;
		
		AtomicBoolean	destroyed = new AtomicBoolean();
		
		private
		ViewInstance(
			I2PHelperPlugin		_plugin,
			UISWTView			_view )
		{	
			plugin	= _plugin;
			view	= _view;
		}
		
		protected void
		initialise(
			Composite	_composite )
		{
			composite	= _composite;
			
			Composite main = new Composite(composite, SWT.NONE);
			GridLayout layout = new GridLayout();
			layout.numColumns = 1;
			layout.marginHeight = 0;
			layout.marginWidth = 0;
			main.setLayout(layout);
			GridData grid_data = new GridData(GridData.FILL_BOTH );
			main.setLayoutData(grid_data);
			
			CTabFolder  tab_folder = new CTabFolder(main, SWT.LEFT);
			tab_folder.setBorderVisible(true);
			tab_folder.setTabHeight(20);
			
			Label lblClose = new Label(tab_folder, SWT.WRAP);
			lblClose.setText("x");
			lblClose.addListener(SWT.MouseUp, new Listener() {
				@Override
				public void handleEvent(Event event) {
					
				}
			});
			tab_folder.setTopRight(lblClose);
			
			grid_data = new GridData(GridData.FILL_BOTH);
			tab_folder.setLayoutData(grid_data);
			
			CTabItem first_stats_item = null;
			
			normal_dht_count = plugin.getDHTCount();
			
			if ( Constants.isCVSVersion()){
				
				proxy_dhts = plugin.getProxyDHTs();
				
			}else{
				
				proxy_dhts = new ArrayList<I2PHelperDHTPluginInterface>();
			}
			
			proxy_dht_count = proxy_dhts.size();
			
			int	total_dhts	= normal_dht_count+proxy_dht_count;
			
			int	total_views = total_dhts*2;		// basic + AZ views
			
			dht_views	= new DHTView[ total_views ];
			ops_views	= new DHTOpsView[ total_views ];

				// views are basic1 basic2 (proxy1,proxy2...) az1 az2 (proxyaz1, proxyaz2...)
				// actually the proxy1/2/... views are dead as there is no non-AZ traffic
			
			for ( int i=0;i<total_views;i++){
								
				int	dht_index;
				
				if ( i < total_dhts ){
					dht_index = i;
				}else{
					dht_index = i - total_dhts;
				}
				
				String stats_text;
				String graph_text;
				
				if ( dht_index < normal_dht_count ){
					stats_text = plugin.getMessageText("azi2phelper.ui.dht_stats" + dht_index);
					graph_text = plugin.getMessageText("azi2phelper.ui.dht_graph" + dht_index);
				}else{
					if ( i < total_dhts ){
						continue;	// skip this view as dead
					}
					String name = proxy_dhts.get(dht_index - normal_dht_count).getName();
					stats_text = name + " " + plugin.getMessageText("azi2phelper.ui.dht_stats3");
					graph_text = name + " " + plugin.getMessageText("azi2phelper.ui.dht_graph3");
				}
				if ( i >= total_dhts ){
					
					stats_text = stats_text + " (AZ)";
					graph_text = graph_text + " (AZ)";
				}
				
					// DHT stats view
					
				CTabItem stats_item = new CTabItem(tab_folder, SWT.NULL);
		
				if ( i == 0 ){
					
					first_stats_item = stats_item;
				}

				stats_item.setText( stats_text );
				
				DHTView dht_view = dht_views[i] = new DHTView( false );
				Composite stats_comp = new Composite( tab_folder, SWT.NULL );
				stats_item.setControl( stats_comp );
				layout = new GridLayout();
				layout.numColumns = 1;
				layout.marginHeight = 0;
				layout.marginWidth = 0;
				stats_comp.setLayout(layout);
				grid_data = new GridData(GridData.FILL_BOTH);
				stats_comp.setLayoutData(grid_data);
				dht_view.initialize( stats_comp );
					
				fixupView( stats_comp );
				
					// DHT Graph view
				
				CTabItem ops_item = new CTabItem(tab_folder, SWT.NULL);
		
				ops_item.setText( graph_text );
				
				DHTOpsView ops_view = ops_views[i] = new DHTOpsView( false, false );
				Composite ops_comp = new Composite( tab_folder, SWT.NULL );
				ops_item.setControl( ops_comp );
				layout = new GridLayout();
				layout.numColumns = 1;
				layout.marginHeight = 0;
				layout.marginWidth = 0;
				ops_comp.setLayout(layout);
				grid_data = new GridData(GridData.FILL_BOTH);
				ops_comp.setLayoutData(grid_data);
				ops_view.initialize( ops_comp );
					
				fixupView( ops_comp );
			}
			
			tab_folder.setSelection( first_stats_item );
			
			view_timer = SimpleTimer.addPeriodicEvent(
					"I2PView:stats",
					1000,
					new TimerEventPerformer() 
					{
						@Override
						public void
						perform(
							TimerEvent event ) 
						{
							if ( dht_views != null ){
								
								Utils.execSWTThread(
									new Runnable()
									{
										@Override
										public void
										run()
										{
											for ( DHTView dht_view: dht_views ){
												
												if ( dht_view == null ){
													
													continue;
												}
												
												dht_view.eventOccurred(
													new UISWTViewEvent() {										
														@Override
														public UISWTView getView() {
															return null;
														}
														
														@Override
														public int getType() {
															return( StatsView.EVENT_PERIODIC_UPDATE );
														}										
														@Override
														public Object getData() {
														
															return null;
														}
													});
											}
										}
									});
							}
						}
					});
		}

		private void
		fixupView(
			Composite	comp )
		{
				// hack to do the same as normal view construction code :(
				
			Control[] children = comp.getChildren();
			for (int i = 0; i < children.length; i++) {
				Control control = children[i];
				Object layoutData = control.getLayoutData();
				if (layoutData == null || !(layoutData instanceof GridData)) {
					GridData grid_data;
					if (children.length == 1)
						grid_data = new GridData(SWT.FILL, SWT.FILL, true, true);
					else
						grid_data = new GridData();
		
					control.setLayoutData(grid_data);
				}
			}	
		}
		
		private void
		refresh(
			UISWTViewEvent		event )
		{
			if ( dht_views != null ){
				
				int	total_dhts	= normal_dht_count+proxy_dht_count;

				for ( int i=0;i<dht_views.length;i++){
					
					DHTView 	dht_view = dht_views[i];
					DHTOpsView 	ops_view = ops_views[i];
					
					if ( dht_view != null && ops_view != null ){
						
						int	dht_index;
						
						if ( i < total_dhts ){
							
							dht_index = i;
							
						}else{
							
							dht_index = i - total_dhts;
						}
						
						if ( dht_index < normal_dht_count ){
							
							I2PHelperRouter router = plugin.getRouter();
							
							if ( router != null ){
									
								I2PHelperDHT dht_helper = router.getAllDHTs()[dht_index].getDHT();
								
								if ( dht_helper instanceof DHTI2P ){
									
									DHT dht;
									
									if ( i < normal_dht_count ){
										
										dht = ((DHTI2P)dht_helper).getDHT();
									}else{
										
										dht = ((DHTI2P)dht_helper).getAZDHT().getDHT();
									}
									
									dht_view.setDHT( dht );
									ops_view.setDHT( dht );
								}
							}
						}else{
							
							I2PHelperDHTPluginInterface proxy_dht = proxy_dhts.get( dht_index - normal_dht_count);
							
							I2PHelperAZDHT dht_helper = proxy_dht.getDHT( 1 );
							
							if ( dht_helper != null ){
								
								DHT dht;
								
								try{
									if ( i < normal_dht_count + proxy_dht_count ){
										
										dht = dht_helper.getBaseDHT();
									}else{
										
										dht = dht_helper.getDHT();
									}
									
									dht_view.setDHT( dht );
									ops_view.setDHT( dht );
									
								}catch( Throwable e ){
									
								}
							}
						}
						
						dht_view.eventOccurred(event);
						ops_view.eventOccurred(event);
					}
				}
			}
		}
		
		private void
		destroy()
		{
			if ( !destroyed.compareAndSet( false, true )){
				
				return;
			}
			
			try{
				composite = null;
				
				if ( view_timer != null ){
					
					view_timer.cancel();
					
					view_timer = null;
				}
				
				if ( dht_views != null ){
					
					for ( int i=0;i<dht_views.length;i++){
						
						DHTView 	dht_view = dht_views[i];
						
						if ( dht_view != null ){
							
							dht_view.delete();
							
							dht_views[i] = null;
						}
						
						DHTOpsView 	ops_view = ops_views[i];
						
						if ( ops_view != null ){
							
							ops_view.delete();
							
							ops_views[i] = null;
						}
					}
				}
			}finally{
									
				view.closeView();
			}
		}
	}
}
