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
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

public class 
I2PHelperUtils 
{
	protected static Properties
	readProperties(
		File		file )
	{
		Properties props = new Properties();
		
		try{
		
			if ( file.exists()){
				
				InputStream is = new FileInputStream( file );
			
				try{
					props.load( new InputStreamReader( is, "UTF-8" ));
					
				}finally{
					
					is.close();
				}
			}
		}catch( Throwable e ){
			
			e.printStackTrace();
		}
		
		return( props );
	}
	
	protected static void
	normalizeProperties(
		Properties	props )
	{
		Set<Object> keys = props.keySet();
		
		for ( Object key: keys ){
			
			Object value = props.get( key );
			
			if ( !(value instanceof String )){
				
				props.put( key, String.valueOf( value ));
			}
		}
	}
	

	protected static void
	writeProperties(
		File		file,
		Properties	_props )
	{
		try{
			Properties props = 
				new Properties()
				{
			    	@Override
				    public Enumeration<Object> keys() {
			    	
			    		List<String> keys = new ArrayList<String>((Set<String>)(Object)keySet());
			    		
			    		Collections.sort( keys );
			    		
			    		return( new Vector<Object>( keys ).elements());
			        }
				};
			
			props.putAll( _props );
			
			FileOutputStream os = new FileOutputStream( file );
			
			try{
				props.store( new OutputStreamWriter(os, "UTF-8" ), "" );
				
			}finally{
				
				os.close();
			}
		}catch( Throwable e ){
			
			e.printStackTrace();
		}
	}
}
