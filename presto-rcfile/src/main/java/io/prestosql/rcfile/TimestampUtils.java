/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.rcfile;

import io.prestosql.spi.block.Block;
import io.prestosql.spi.type.LongTimestamp;
import io.prestosql.spi.type.TimestampType;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static io.prestosql.spi.type.Timestamps.MICROSECONDS_PER_SECOND;
import static io.prestosql.spi.type.Timestamps.PICOSECONDS_PER_MICROSECOND;
import static io.prestosql.spi.type.Timestamps.PICOSECONDS_PER_NANOSECOND;
import static java.lang.Math.floorDiv;
import static java.lang.Math.floorMod;
import static java.lang.Math.toIntExact;

public final class TimestampUtils
{
    private TimestampUtils() {}

    public static LocalDateTime getLocalDateTime(TimestampType type, Block block, int position)
    {
        if (block.isNull(position)) {
            return null;
        }
        long epochMicros;
        long picosOfSecond;
        if (type.isShort()) {
            epochMicros = type.getLong(block, position);
            picosOfSecond = (long) floorMod(epochMicros, MICROSECONDS_PER_SECOND) * PICOSECONDS_PER_MICROSECOND;
        }
        else {
            LongTimestamp longTimestamp = (LongTimestamp) type.getObject(block, position);
            epochMicros = longTimestamp.getEpochMicros();
            picosOfSecond = (long) floorMod(epochMicros, MICROSECONDS_PER_SECOND) * PICOSECONDS_PER_MICROSECOND + longTimestamp.getPicosOfMicro();
        }
        long epochSeconds = floorDiv(epochMicros, MICROSECONDS_PER_SECOND);
        // no rounding since the the data has nanosecond precision, at most
        int nanosOfSecond = toIntExact(picosOfSecond / PICOSECONDS_PER_NANOSECOND);

        return LocalDateTime.ofEpochSecond(epochSeconds, nanosOfSecond, ZoneOffset.UTC);
    }
}
