/*
 * Created on 1 Nov 2006
 * Created by Paul Gardner
 * Copyright (C) 2006 Aelitis, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 * AELITIS, SAS au capital de 63.529,40 euros
 * 8 Allee Lenotre, La Grille Royale, 78600 Le Mesnil le Roi, France.
 *
 */


package org.parg.azureus.plugins.networks.i2p;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;




import com.biglybt.core.tracker.server.impl.tcp.TRTrackerServerTCP;
import com.biglybt.core.tracker.server.impl.tcp.nonblocking.TRNonBlockingServer;
import com.biglybt.core.tracker.server.impl.tcp.nonblocking.TRNonBlockingServerProcessor;
import com.biglybt.core.tracker.server.impl.tcp.nonblocking.TRNonBlockingServerProcessorFactory;
import com.biglybt.core.util.AsyncController;
import com.biglybt.core.util.BEncoder;
import com.biglybt.core.util.Constants;
import org.parg.azureus.plugins.networks.i2p.router.I2PHelperRouter;
import org.parg.azureus.plugins.networks.i2p.snarkdht.NodeInfo;

public class 
I2PHelperBootstrapServer 
{
	private static final String	NL			= "\015\012";

	private static final byte[]	HTTP_REPLY_START = (
			"HTTP/1.1 200 OK" + NL + 
			"Content-Type: application/octet-stream" + NL +
			"Server: " + Constants.APP_NAME + " " + Constants.getCurrentVersion() + NL +
			"Connection: close" + NL +
			"Content-Length: ").getBytes();
	
	private I2PHelperRouter		router;
	
	private int request_count;
	
	public
	I2PHelperBootstrapServer(
		int					_port,
		I2PHelperRouter		_router )
	{
			// note this is no longer used, replaced by i2pbootplugin
		
		router	= _router;
		
		try{
			new TRNonBlockingServer( 
					"I2pBootstrap", 
					_port, 
					null, 
					false,
					new TRNonBlockingServerProcessorFactory()
					{
						@Override
						public TRNonBlockingServerProcessor
						create(
							TRTrackerServerTCP		_server,
							SocketChannel			_socket )
						{
							return( new NonBlockingProcessor( _server, _socket ));
						}
					});
		
			System.out.println( "HTTP processor initialised on port " + _port );

		}catch( Throwable e ){
			
			System.out.println( "HTTP processor failed to initialise on port " + _port );
			
			e.printStackTrace();
		}
	}
	
	public void
	destroy()
	{
	}
	
	public String
	getString()
	{
		return( "req=" + request_count );
	}
	
	protected class
	NonBlockingProcessor
		extends TRNonBlockingServerProcessor
	{
		protected
		NonBlockingProcessor(
			TRTrackerServerTCP		_server,
			SocketChannel			_socket )
		{
			super( _server, _socket );
		}
		
		@Override
		protected ByteArrayOutputStream
		process(
			String 						input_header, 
			String 						lowercase_input_header, 
			String 						url_path, 
			final InetSocketAddress 	client_address, 
			boolean 					announce_and_scrape_only, 
			InputStream 				is,
			AsyncController				async )
		
			throws IOException 
		{
			ByteArrayOutputStream os	= new ByteArrayOutputStream( 16*1024 );
			
			request_count++;
				
			I2PHelperDHT dht = router.selectDHT().getDHT();
			
			List<NodeInfo> nodes = dht.getNodesForBootstrap( 8 );
			
			Map reply = new HashMap();
				
			List l_nodes = new ArrayList( nodes.size());
			
			reply.put( "nodes", l_nodes );
			
			for ( NodeInfo node: nodes ){
				
				Map	m = new HashMap();
				
				byte[]		nid	 = node.getNID().getData();
				int			port = node.getPort();
				byte[]		dest = node.getDestination().toByteArray();
				
				m.put( "n", nid );
				m.put( "p", port );
				m.put( "d", dest );
				
				l_nodes.add( m );
			}
			
			byte[]	encoded = BEncoder.encode( reply );
				
			os.write( HTTP_REPLY_START );
			os.write( ( encoded.length + NL + NL ).getBytes() );
				
			os.write( encoded );
			
			return( os );
		}
		
		@Override
		protected void
		failed()
		{
		}
	}
}
