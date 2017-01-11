//
//   Copyright 2017  Cityzen Data
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//

package io.warp10.standalone;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import io.warp10.continuum.TimeSource;
import io.warp10.continuum.gts.GTSDecoder;
import io.warp10.continuum.gts.GTSEncoder;
import io.warp10.continuum.sensision.SensisionConstants;
import io.warp10.sensision.Sensision;

public class InMemoryChunkSet {
  // Keep track whether or not a GTSEncoder has all its timestamps in chronological order, speeds up fetching
  // 
  
  private final GTSEncoder[] chunks;
  
  /**
   * End timestamp of each chunk
   */
  private final long[] chunkends;
  
  /**
   * Flags indicating if timestamps are increasingly monotonic
   */
  private final BitSet chronological;
  
  /**
   * Last timestamp encountered in a chunk
   */
  private final long[] lasttimestamp;
  
  /**
   * Length of chunks in time units
   */
  private final long chunklen;
  
  /**
   * Number of chunks
   */
  private final int chunkcount;
  
  public InMemoryChunkSet(int chunkcount, long chunklen) {
    this.chunks = new GTSEncoder[chunkcount];
    this.chunkends = new long[chunkcount];
    this.chronological = new BitSet(chunkcount);
    this.lasttimestamp = new long[chunkcount];
    this.chunklen = chunklen;
    this.chunkcount = chunkcount;
  }
  
  /**
   * Store the content of a GTSEncoder in the various chunks we manage
   * 
   * @param encoder The GTSEncoder instance to store
   */
  public void store(GTSEncoder encoder) throws IOException {
    
    // Get the current time
    long now = TimeSource.getTime();
    long lastChunkEnd = chunkEnd(now);
    long firstChunkStart = lastChunkEnd - (chunkcount * chunklen) + 1;

    // Get a decoder without copying the encoder array
    GTSDecoder decoder = encoder.getUnsafeDecoder(false);
    
    int lastchunk = -1;

    GTSEncoder chunkEncoder = null;

    while(decoder.next()) {
      long timestamp = decoder.getTimestamp();
      
      // Ignore timestamp if it is not in the valid range
      if (timestamp < firstChunkStart || timestamp > lastChunkEnd) {
        continue;
      }
      
      // Compute the chunkid
      int chunkid = chunk(timestamp);
    
      if (chunkid != lastchunk) {
        chunkEncoder = null;
      
        synchronized(this.chunks) {
          // Is the chunk non existent or has expired?
          if (null == this.chunks[chunkid] || this.chunkends[chunkid] < firstChunkStart) {
            long end = chunkEnd(timestamp);
            this.chunks[chunkid] = new GTSEncoder(0L);
            this.lasttimestamp[chunkid] = end - this.chunklen;
            this.chronological.set(chunkid);
            this.chunkends[chunkid] = end;          
          }
          
          chunkEncoder = this.chunks[chunkid];
          
          if (timestamp < this.lasttimestamp[chunkid]) {
            this.chronological.set(chunkid, false);
          }
          this.lasttimestamp[chunkid] = timestamp;
        }
        
        lastchunk = chunkid;
      }

      chunkEncoder.addValue(timestamp, decoder.getLocation(), decoder.getElevation(), decoder.getValue());      
    }
  }
  
  /**
   * Compute the chunk id given a timestamp.
   * @param timestamp
   * @return
   */
  private int chunk(long timestamp) {
    int chunkid;
    
    if (timestamp >= 0) {
      chunkid = (int) ((timestamp / chunklen) % chunkcount);
    } else {
      chunkid = chunkcount + (int) ((((timestamp + 1) / chunklen) % chunkcount) - 1);
      //chunkid = chunkcount - (int) ((- (timestamp + 1) / chunklen) % chunkcount);
    }
    
    return chunkid;
  }
  
  /**
   * Compute the end timestamp of the chunk this timestamp
   * belongs to.
   * 
   * @param timestamp
   * @return
   */
  private long chunkEnd(long timestamp) {    
    long end;
    
    if (timestamp > 0) {
      end = ((timestamp / chunklen) * chunklen) + chunklen - 1;
    } else {
      end = ((((timestamp + 1) / chunklen) - 1) * chunklen) + chunklen - 1;
    }
    
    return end;
  }
  
  /**
   * Fetches some data from this chunk set
   * 
   * @param now The end timestamp to consider (inclusive).
   * @param timespan The timespan or value count to consider.
   * @return
   */
  public GTSDecoder fetch(long now, long timespan) throws IOException {
    return fetchEncoder(now, timespan).getUnsafeDecoder(false);
  }
  
  public List<GTSDecoder> getDecoders() {
    List<GTSDecoder> decoders = new ArrayList<GTSDecoder>();
    
    synchronized (this.chunks) {
      for (int i = 0; i < 0; i++) {
        if (null == this.chunks[i]) {
          continue;
        }
        decoders.add(this.chunks[i].getUnsafeDecoder(false));
      }
    }
    
    return decoders;
  }
  
  public GTSEncoder fetchEncoder(long now, long timespan) throws IOException {

    // Clean up first
    clean(TimeSource.getTime());
    
    if (timespan < 0) {
      return fetchCountEncoder(now, -timespan);
    }
    
    //
    // Determine the chunk id of 'now'
    // We offset it by chunkcount so we can safely decrement and
    // still have a positive remainder when doing a modulus
    //
    int nowchunk = chunk(now) + this.chunkcount;
    
    // Compute the first timestamp (included)
    long firstTimestamp = now - timespan + 1;
    
    GTSEncoder encoder = new GTSEncoder(0L);
    
    for (int i = 0; i < this.chunkcount; i++) {
      int chunk = (nowchunk - i) % this.chunkcount;
      
      GTSDecoder chunkDecoder = null;
      
      synchronized(this.chunks) {
        // Ignore a given chunk if it does not intersect our current range
        if (this.chunkends[chunk] < firstTimestamp || (this.chunkends[chunk] - this.chunklen) >= now) {
          continue;
        }
        
        // Extract a decoder to scan the chunk
        if (null != this.chunks[chunk]) {
          chunkDecoder = this.chunks[chunk].getUnsafeDecoder(false);
        }
      }
      
      if (null == chunkDecoder) {
        continue;
      }
      
      // Merge the data from chunkDecoder which is in the requested range in 'encoder'
      while(chunkDecoder.next()) {
        long ts = chunkDecoder.getTimestamp();
        
        if (ts > now || ts < firstTimestamp) {
          continue;
        }
        
        encoder.addValue(ts, chunkDecoder.getLocation(), chunkDecoder.getElevation(), chunkDecoder.getValue());
      }
    }

    return encoder;
  }
  
  private GTSDecoder fetchCount(long now, long count) throws IOException {
    return fetchCountEncoder(now, count).getUnsafeDecoder(false);
  }
  
  private GTSEncoder fetchCountEncoder(long now, long count) throws IOException {

    //
    // Determine the chunk id of 'now'
    // We offset it by chunkcount so we can safely decrement and
    // still have a positive remainder when doing a modulus
    //
    int nowchunk = chunk(now) + this.chunkcount;
    
    GTSEncoder encoder = new GTSEncoder();
    
    // Initialize the number of datapoints to fetch
    long nvalues = count;
    
    // Loop over the chunks
    for (int i = 0; i < this.chunkcount; i++) {
      int chunk = (nowchunk - i) % this.chunkcount;
      
      GTSDecoder chunkDecoder = null;
      boolean inorder = true;
      long chunkEnd = -1;
      
      synchronized(this.chunks) {
        // Ignore a given chunk if it is after 'now'
        if (this.chunkends[chunk] - this.chunklen >= now) {
          continue;
        }
        
        // Extract a decoder to scan the chunk
        if (null != this.chunks[chunk]) {
          chunkDecoder = this.chunks[chunk].getUnsafeDecoder(false);
          inorder = this.chronological.get(chunk);
          chunkEnd = this.chunkends[chunk];
        }
      }
      
      if (null == chunkDecoder) {
        continue;
      }
      
      // We now have a chunk, we will treat it differently depending if
      // it is in chronological order or not
      
      if (inorder) {
        //
        // If the end timestamp of the chunk is before 'now' and the
        // chunk contains less than the remaining values we need to fetch
        // we can add everything.
        //
        
        if (chunkEnd <= now && chunkDecoder.getCount() <= nvalues) {
          while(chunkDecoder.next()) {
            encoder.addValue(chunkDecoder.getTimestamp(), chunkDecoder.getLocation(), chunkDecoder.getElevation(), chunkDecoder.getValue());
            nvalues--;
          }
        } else if (chunkDecoder.getCount() <= nvalues) {
          // We have a chunk with chunkEnd > 'now' but which contains less than nvalues,
          // so we add all the values whose timestamp is <= 'now'
          while(chunkDecoder.next()) {
            long ts = chunkDecoder.getTimestamp();
            if (ts > now) {
              // we can break because we know the encoder is in chronological order.
              break;
            }
            encoder.addValue(ts, chunkDecoder.getLocation(), chunkDecoder.getElevation(), chunkDecoder.getValue());
            nvalues--;
          }          
        } else {
          //
          // The chunk has more values than what we need.
          // If the end of the chunk is <= now then we know we must skip count - nvalues and
          // add the rest to the result.
          // Otherwise it's a little trickier
          //
          
          if (chunkEnd <= now) {
            long skip = chunkDecoder.getCount() - nvalues;
            while(skip > 0 && chunkDecoder.next()) {
              skip--;
            }
            while(chunkDecoder.next()) {
              encoder.addValue(chunkDecoder.getTimestamp(), chunkDecoder.getLocation(), chunkDecoder.getElevation(), chunkDecoder.getValue());
              nvalues--;
            }          
          } else {
            // We will transfer the datapoints whose timestamp is <= now in a an intermediate encoder
            GTSEncoder intenc = new GTSEncoder();
            while(chunkDecoder.next()) {
              long ts = chunkDecoder.getTimestamp();
              if (ts > now) {
                // we can break because we know the encoder is in chronological order.
                break;
              }
              intenc.addValue(ts, chunkDecoder.getLocation(), chunkDecoder.getElevation(), chunkDecoder.getValue());
              nvalues--;
            }
            // Then transfer the intermediate encoder to the result
            chunkDecoder = intenc.getUnsafeDecoder(false);
            long skip = chunkDecoder.getCount() - nvalues;
            while(skip > 0 && chunkDecoder.next()) {
              skip--;
            }
            while(chunkDecoder.next()) {
              encoder.addValue(chunkDecoder.getTimestamp(), chunkDecoder.getLocation(), chunkDecoder.getElevation(), chunkDecoder.getValue());
              nvalues--;
            }                      
          }
        }
      } else {
        // The chunk decoder is not in chronological order...
        
        // If the chunk decoder end is <= 'now' and the decoder contains less values than
        // what is still needed, add everything.
        
        if (chunkEnd <= now && chunkDecoder.getCount() <= nvalues) {
          while(chunkDecoder.next()) {
            encoder.addValue(chunkDecoder.getTimestamp(), chunkDecoder.getLocation(), chunkDecoder.getElevation(), chunkDecoder.getValue());
            nvalues--;
          }          
        } else if(chunkDecoder.getCount() <= nvalues) {
          // We have a chunk with chunkEnd > 'now' but which contains less than nvalues,
          // so we add all the values whose timestamp is <= 'now'
          while(chunkDecoder.next()) {
            long ts = chunkDecoder.getTimestamp();
            if (ts > now) {
              // we can break because we know the encoder is in chronological order.
              break;
            }
            encoder.addValue(ts, chunkDecoder.getLocation(), chunkDecoder.getElevation(), chunkDecoder.getValue());
            nvalues--;
          }          
        } else {
          // We have a chunk which has more values than what we need and/or whose end
          // is after 'now'
          // We will transfer the datapoints whose timestamp is <= now in a an intermediate encoder
          GTSEncoder intenc = new GTSEncoder();
          while(chunkDecoder.next()) {
            long ts = chunkDecoder.getTimestamp();
            if (ts > now) {
              continue;
            }
            intenc.addValue(ts, chunkDecoder.getLocation(), chunkDecoder.getElevation(), chunkDecoder.getValue());
            nvalues--;
          }
          //
          // Now we need to extract the ticks of the intermediary encoder
          //
          long[] ticks = new long[(int) intenc.getCount()];
          int k = 0;
          chunkDecoder = intenc.getUnsafeDecoder(false);
          while(chunkDecoder.next()) {
            ticks[k++] = chunkDecoder.getTimestamp();
          }
          // Now sort the ticks
          Arrays.sort(ticks);
          // We must skip values whose timestamp is <= ticks[ticks.length - nvalues]
          long skipbelow = ticks[ticks.length - (int) nvalues];
          
          // Then transfer the intermediate encoder to the result
          chunkDecoder = intenc.getUnsafeDecoder(false);
          while(chunkDecoder.next()) {
            long ts = chunkDecoder.getTimestamp();
            if (ts < skipbelow) {
              continue;
            }
            encoder.addValue(ts, chunkDecoder.getLocation(), chunkDecoder.getElevation(), chunkDecoder.getValue());
            nvalues--;
          }                      
        }
      }      
    }
    return encoder;
  }
  
  /**
   * Compute the total number of datapoints stored in this chunk set.
   * 
   * @return
   */
  public long getCount() {
    long count = 0L;
    
    for (GTSEncoder encoder: chunks) {
      if (null != encoder) {
        count += encoder.getCount();
      }
    }
    
    return count;
  }
  
  /**
   * Compute the total size occupied by the encoders in this chunk set
   * 
   * @return
   */
  public long getSize() {
    long size = 0L;
    
    for (GTSEncoder encoder: chunks) {
      if (null != encoder) {
        size += encoder.size();
      }
    }
    
    return size;
  }
  
  /**
   * Clean expired chunks according to 'now'
   * 
   * @param now
   */
  public void clean(long now) {
    long cutoff = chunkEnd(now) - this.chunkcount * this.chunklen;
    int dropped = 0;
    synchronized(this.chunks) {
      for (int i = 0; i < this.chunks.length; i++) {
        if (null == this.chunks[i]) {
          continue;
        }
        if (this.chunkends[i] <= cutoff) {
          this.chunks[i] = null;
          dropped++;
        }
      }
    }
    
    Sensision.update(SensisionConstants.SENSISION_CLASS_CONTINUUM_STANDALONE_INMEMORY_CHUNKS_DROPPED, Sensision.EMPTY_LABELS, dropped);
  }
}
