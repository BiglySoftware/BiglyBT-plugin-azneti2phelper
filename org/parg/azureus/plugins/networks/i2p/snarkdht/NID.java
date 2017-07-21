package org.parg.azureus.plugins.networks.i2p.snarkdht;
/*
 *  From zzzot, modded and relicensed to GPLv2
 */

import net.i2p.crypto.SHA1Hash;
import net.i2p.util.Clock;

/**
 *  A 20-byte peer ID, used as a Map key in lots of places.
 *  Must be public for constructor in KBucketSet.generateRandomKey()
 *
 * @since 0.9.2
 * @author zzz
 */
public class NID extends SHA1Hash {

    private long created;
    private long lastAlive;
    private long firstFailed;
    
    private int fails;

    private static final int MAX_FAILS = 2;

    public NID() {
        super(null);
        
        created = Clock.getInstance().now();
    }

    public NID(byte[] data) {
        super(data);
        
        created = Clock.getInstance().now();
    }

    public long 
    getCreated() 
    {
        return created;
    }

    public long
    getLastKnown()
    {
    	return( lastAlive== 0?created:lastAlive );
    }
    public long
    getLastAlive()
    {
    	return( lastAlive );
    }
    
    public long
    getFirstFailed()
    {
    	return( firstFailed );
    }
    
    public int
    getFailCount()
    {
    	return( fails );
    }
    
    public void
    setAlive()
    {
    	lastAlive = Clock.getInstance().now();
    	fails = 0;
    }
    
    public boolean 
    isAlive()
    {
    	return( fails == 0 && lastAlive > 0 );
    }
    
    public void
    resetCreated()
    {
    	created = Clock.getInstance().now();
    }


    /**
     *  @return if more than max timeouts
     */
    public boolean timeout() {
    	if ( fails == 0 ){
    		firstFailed = Clock.getInstance().now();
    	}
        return ++fails > MAX_FAILS;
    }
    
    public String
    toString()
    {
    	long now = Clock.getInstance().now();
    	
    	return( super.toString() + "age=" + (now-created) + ",la=" + (lastAlive==0?"n":(now-lastAlive)) + ",ff=" + (firstFailed==0?"n":(now-firstFailed))+",f=" + fails );
    }
}
