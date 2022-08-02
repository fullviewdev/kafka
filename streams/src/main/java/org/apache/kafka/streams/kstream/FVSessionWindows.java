/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.kstream;

import org.apache.kafka.streams.processor.TimestampExtractor;

import java.time.Duration;
import java.util.Objects;

import static java.time.Duration.ofMillis;
import static java.time.Duration.ofMinutes;
import static org.apache.kafka.streams.internals.ApiUtils.prepareMillisCheckFailMsgPrefix;
import static org.apache.kafka.streams.internals.ApiUtils.validateMillisecondDuration;
import static org.apache.kafka.streams.kstream.Windows.DEPRECATED_DEFAULT_24_HR_GRACE_PERIOD;
import static org.apache.kafka.streams.kstream.Windows.NO_GRACE_PERIOD;

/**
 * A session based window specification used for aggregating events into sessions.
 * <p>
 * Sessions represent a period of activity separated by a defined gap of inactivity.
 * Any events processed that fall within the inactivity gap of any existing sessions are merged into the existing sessions.
 * If the event falls outside of the session gap then a new session will be created.
 * <p>
 * For example, if we have a session gap of 5 and the following data arrives:
 * <pre>
 * +--------------------------------------+
 * |    key    |    value    |    time    |
 * +-----------+-------------+------------+
 * |    A      |     1       |     10     |
 * +-----------+-------------+------------+
 * |    A      |     2       |     12     |
 * +-----------+-------------+------------+
 * |    A      |     3       |     20     |
 * +-----------+-------------+------------+
 * </pre>
 * We'd have 2 sessions for key A.
 * One starting from time 10 and ending at time 12 and another starting and ending at time 20.
 * The length of the session is driven by the timestamps of the data within the session.
 * Thus, session windows are no fixed-size windows (c.f. {@link TimeWindows} and {@link JoinWindows}).
 * <p>
 * If we then received another record:
 * <pre>
 * +--------------------------------------+
 * |    key    |    value    |    time    |
 * +-----------+-------------+------------+
 * |    A      |     4       |     16     |
 * +-----------+-------------+------------+
 * </pre>
 * The previous 2 sessions would be merged into a single session with start time 10 and end time 20.
 * The aggregate value for this session would be the result of aggregating all 4 values.
 * <p>
 * For time semantics, see {@link TimestampExtractor}.
 *
 * @see TimeWindows
 * @see UnlimitedWindows
 * @see JoinWindows
 * @see KGroupedStream#windowedBy(FVSessionWindows)
 * @see TimestampExtractor
 */
public final class FVSessionWindows {

    static final long DEFAULT_MAX_TIME = 10L;

    private final long gapMs;

    private final long maxTimeMs;

    private final long graceMs;

    private FVSessionWindows(final long gapMs, final long graceMs, long maxMs) {
        this.gapMs = gapMs;
        this.graceMs = graceMs;
        this.maxTimeMs = maxMs;

        if (gapMs <= 0) {
            throw new IllegalArgumentException("Gap time cannot be zero or negative.");
        }

        if (graceMs < 0) {
            throw new IllegalArgumentException("Grace period must not be negative.");
        }

        if (maxMs < 0) {
            throw new IllegalArgumentException("Max time period must not be negative.");
        }
    }

    /**
     * Creates a new window specification with the specified inactivity gap and max time.
     * <p>
     * Note that new events may change the boundaries of session windows, so aggressive
     * close times can lead to surprising results in which an out-of-order event is rejected and then
     * a subsequent event moves the window boundary forward.
     * <p>
     * CAUTION: Using this method implicitly sets the grace period to zero, which means that any out-of-order
     * records arriving after the window ends are considered late and will be dropped.
     *
     * @param inactivityGap the gap of inactivity between sessions
     * @return a window definition with the window size and no grace period. Note that this means out-of-order records arriving after the window end will be dropped
     * @throws IllegalArgumentException if {@code inactivityGap} is zero or negative or can't be represented as {@code long milliseconds}
     */
    public static FVSessionWindows ofInactivityGapWithNoGrace(final Duration inactivityGap) {
        return ofInactivityGapAndGrace(inactivityGap, ofMillis(NO_GRACE_PERIOD));
    }

    /**
     * Creates a new window specification with the specified inactivity gap.
     * <p>
     * Note that new events may change the boundaries of session windows, so aggressive
     * close times can lead to surprising results in which an out-of-order event is rejected and then
     * a subsequent event moves the window boundary forward.
     * <p>
     * Using this method explicitly sets the grace period to the duration specified by {@code afterWindowEnd}, which
     * means that only out-of-order records arriving more than the grace period after the window end will be dropped.
     * The window close, after which any incoming records are considered late and will be rejected, is defined as
     * {@code windowEnd + afterWindowEnd}
     *
     * @param inactivityGap  the gap of inactivity between sessions
     * @param afterWindowEnd The grace period to admit out-of-order events to a window.
     * @return A SessionWindows object with the specified inactivity gap and grace period
     * @throws IllegalArgumentException if {@code inactivityGap} is zero or negative or can't be represented as {@code long milliseconds}
     *                                  if {@code afterWindowEnd} is negative or can't be represented as {@code long milliseconds}
     */
    public static FVSessionWindows ofInactivityGapAndGrace(final Duration inactivityGap, final Duration afterWindowEnd) {
        return ofInactivityGapAndMaxAndGrace(inactivityGap, ofMinutes(DEFAULT_MAX_TIME), ofMillis(NO_GRACE_PERIOD));
    }

    public static FVSessionWindows ofInactivityGapAndMaxTime(final Duration inactivityGap, final Duration maxTime) {
        return ofInactivityGapAndMaxAndGrace(inactivityGap, maxTime, ofMillis(NO_GRACE_PERIOD));
    }

    public static FVSessionWindows ofInactivityGapAndMaxAndGrace(final Duration inactivityGap, final Duration maxTime, final Duration afterWindowEnd) {
        final String inactivityGapMsgPrefix = prepareMillisCheckFailMsgPrefix(inactivityGap, "inactivityGap");
        final long inactivityGapMs = validateMillisecondDuration(inactivityGap, inactivityGapMsgPrefix);

        final String maxTimeMsgPrefix = prepareMillisCheckFailMsgPrefix(maxTime, "maxTime");
        final long maxTimeMs = validateMillisecondDuration(maxTime, maxTimeMsgPrefix);

        final String afterWindowEndMsgPrefix = prepareMillisCheckFailMsgPrefix(afterWindowEnd, "afterWindowEnd");
        final long afterWindowEndMs = validateMillisecondDuration(afterWindowEnd, afterWindowEndMsgPrefix);

        return new FVSessionWindows(inactivityGapMs, afterWindowEndMs, maxTimeMs);
    }

    public long gracePeriodMs() {
        return graceMs;
    }

    /**
     * Return the specified gap for the session windows in milliseconds.
     *
     * @return the inactivity gap of the specified windows
     */
    public long inactivityGap() {
        return gapMs;
    }

    public long maxTime() {
        return maxTimeMs;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final FVSessionWindows that = (FVSessionWindows) o;
        return gapMs == that.gapMs &&
                maxTimeMs == that.maxTimeMs &&
                graceMs == that.graceMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(gapMs, maxTimeMs, graceMs);
    }

    @Override
    public String toString() {
        return "FVSessionWindows{" +
                "gapMs=" + gapMs +
                ", maxTimeMs=" + maxTimeMs +
                ", graceMs=" + graceMs +
                '}';
    }
}
