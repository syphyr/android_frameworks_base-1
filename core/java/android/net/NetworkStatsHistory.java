/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net;

import android.os.Parcel;
import android.os.Parcelable;

import java.io.CharArrayWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Collection of historical network statistics, recorded into equally-sized
 * "buckets" in time. Internally it stores data in {@code long} series for more
 * efficient persistence.
 * <p>
 * Each bucket is defined by a {@link #bucketStart} timestamp, and lasts for
 * {@link #bucketDuration}. Internally assumes that {@link #bucketStart} is
 * sorted at all times.
 *
 * @hide
 */
public class NetworkStatsHistory implements Parcelable {
    private static final int VERSION = 1;

    /** {@link #uid} value when UID details unavailable. */
    public static final int UID_ALL = -1;

    // TODO: teach about zigzag encoding to use less disk space
    // TODO: teach how to convert between bucket sizes

    public final int networkType;
    public final String identity;
    public final int uid;
    public final long bucketDuration;

    int bucketCount;
    long[] bucketStart;
    long[] rx;
    long[] tx;

    public NetworkStatsHistory(int networkType, String identity, int uid, long bucketDuration) {
        this.networkType = networkType;
        this.identity = identity;
        this.uid = uid;
        this.bucketDuration = bucketDuration;
        bucketStart = new long[0];
        rx = new long[0];
        tx = new long[0];
        bucketCount = bucketStart.length;
    }

    public NetworkStatsHistory(Parcel in) {
        networkType = in.readInt();
        identity = in.readString();
        uid = in.readInt();
        bucketDuration = in.readLong();
        bucketStart = readLongArray(in);
        rx = in.createLongArray();
        tx = in.createLongArray();
        bucketCount = bucketStart.length;
    }

    /** {@inheritDoc} */
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(networkType);
        out.writeString(identity);
        out.writeInt(uid);
        out.writeLong(bucketDuration);
        writeLongArray(out, bucketStart, bucketCount);
        writeLongArray(out, rx, bucketCount);
        writeLongArray(out, tx, bucketCount);
    }

    public NetworkStatsHistory(DataInputStream in) throws IOException {
        final int version = in.readInt();
        networkType = in.readInt();
        identity = in.readUTF();
        uid = in.readInt();
        bucketDuration = in.readLong();
        bucketStart = readLongArray(in);
        rx = readLongArray(in);
        tx = readLongArray(in);
        bucketCount = bucketStart.length;
    }

    public void writeToStream(DataOutputStream out) throws IOException {
        out.writeInt(VERSION);
        out.writeInt(networkType);
        out.writeUTF(identity);
        out.writeInt(uid);
        out.writeLong(bucketDuration);
        writeLongArray(out, bucketStart, bucketCount);
        writeLongArray(out, rx, bucketCount);
        writeLongArray(out, tx, bucketCount);
    }

    /** {@inheritDoc} */
    public int describeContents() {
        return 0;
    }

    /**
     * Record that data traffic occurred in the given time range. Will
     * distribute across internal buckets, creating new buckets as needed.
     */
    public void recordData(long start, long end, long rx, long tx) {
        // create any buckets needed by this range
        ensureBuckets(start, end);

        // distribute data usage into buckets
        final long duration = end - start;
        for (int i = bucketCount - 1; i >= 0; i--) {
            final long curStart = bucketStart[i];
            final long curEnd = curStart + bucketDuration;

            // bucket is older than record; we're finished
            if (curEnd < start) break;
            // bucket is newer than record; keep looking
            if (curStart > end) continue;

            final long overlap = Math.min(curEnd, end) - Math.max(curStart, start);
            if (overlap > 0) {
                this.rx[i] += rx * overlap / duration;
                this.tx[i] += tx * overlap / duration;
            }
        }
    }

    /**
     * Ensure that buckets exist for given time range, creating as needed.
     */
    private void ensureBuckets(long start, long end) {
        // normalize incoming range to bucket boundaries
        start -= start % bucketDuration;
        end += (bucketDuration - (end % bucketDuration)) % bucketDuration;

        for (long now = start; now < end; now += bucketDuration) {
            // try finding existing bucket
            final int index = Arrays.binarySearch(bucketStart, 0, bucketCount, now);
            if (index < 0) {
                // bucket missing, create and insert
                insertBucket(~index, now);
            }
        }
    }

    /**
     * Insert new bucket at requested index and starting time.
     */
    private void insertBucket(int index, long start) {
        // create more buckets when needed
        if (bucketCount + 1 > bucketStart.length) {
            final int newLength = bucketStart.length + 10;
            bucketStart = Arrays.copyOf(bucketStart, newLength);
            rx = Arrays.copyOf(rx, newLength);
            tx = Arrays.copyOf(tx, newLength);
        }

        // create gap when inserting bucket in middle
        if (index < bucketCount) {
            final int dstPos = index + 1;
            final int length = bucketCount - index;

            System.arraycopy(bucketStart, index, bucketStart, dstPos, length);
            System.arraycopy(rx, index, rx, dstPos, length);
            System.arraycopy(tx, index, tx, dstPos, length);
        }

        bucketStart[index] = start;
        rx[index] = 0;
        tx[index] = 0;
        bucketCount++;
    }

    /**
     * Remove buckets older than requested cutoff.
     */
    public void removeBucketsBefore(long cutoff) {
        int i;
        for (i = 0; i < bucketCount; i++) {
            final long curStart = bucketStart[i];
            final long curEnd = curStart + bucketDuration;

            // cutoff happens before or during this bucket; everything before
            // this bucket should be removed.
            if (curEnd > cutoff) break;
        }

        if (i > 0) {
            final int length = bucketStart.length;
            bucketStart = Arrays.copyOfRange(bucketStart, i, length);
            rx = Arrays.copyOfRange(rx, i, length);
            tx = Arrays.copyOfRange(tx, i, length);
            bucketCount -= i;
        }
    }

    public void dump(String prefix, PrintWriter pw) {
        // TODO: consider stripping identity when dumping
        pw.print(prefix);
        pw.print("NetworkStatsHistory: networkType="); pw.print(networkType);
        pw.print(" identity="); pw.print(identity);
        pw.print(" uid="); pw.println(uid);
        for (int i = 0; i < bucketCount; i++) {
            pw.print(prefix);
            pw.print("  timestamp="); pw.print(bucketStart[i]);
            pw.print(" rx="); pw.print(rx[i]);
            pw.print(" tx="); pw.println(tx[i]);
        }
    }

    @Override
    public String toString() {
        final CharArrayWriter writer = new CharArrayWriter();
        dump("", new PrintWriter(writer));
        return writer.toString();
    }

    public static final Creator<NetworkStatsHistory> CREATOR = new Creator<NetworkStatsHistory>() {
        public NetworkStatsHistory createFromParcel(Parcel in) {
            return new NetworkStatsHistory(in);
        }

        public NetworkStatsHistory[] newArray(int size) {
            return new NetworkStatsHistory[size];
        }
    };

    private static long[] readLongArray(DataInputStream in) throws IOException {
        final int size = in.readInt();
        final long[] values = new long[size];
        for (int i = 0; i < values.length; i++) {
            values[i] = in.readLong();
        }
        return values;
    }

    private static void writeLongArray(DataOutputStream out, long[] values, int size) throws IOException {
        if (size > values.length) {
            throw new IllegalArgumentException("size larger than length");
        }
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            out.writeLong(values[i]);
        }
    }

    private static long[] readLongArray(Parcel in) {
        final int size = in.readInt();
        final long[] values = new long[size];
        for (int i = 0; i < values.length; i++) {
            values[i] = in.readLong();
        }
        return values;
    }

    private static void writeLongArray(Parcel out, long[] values, int size) {
        if (size > values.length) {
            throw new IllegalArgumentException("size larger than length");
        }
        out.writeInt(size);
        for (int i = 0; i < size; i++) {
            out.writeLong(values[i]);
        }
    }

}
