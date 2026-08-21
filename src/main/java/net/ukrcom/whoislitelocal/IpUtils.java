/*
 * Copyright 2025 olden.
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
package net.ukrcom.whoislitelocal;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;
import java.net.InetAddress;
import java.net.UnknownHostException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class IpUtils {

    private IpUtils() {
    }

    /**
     * Перетворює IPv4 адресу та кількість адрес у CIDR-нотацію (наприклад,
     * 212.90.160.0, 8192 -> 212.90.160.0/19).
     *
     * @param ipAddress
     * @param count
     * @return
     * @throws java.net.UnknownHostException
     */
    public static String ipv4ToCidr(String ipAddress, int count) throws
            UnknownHostException {
        if (count <= 0) {
            throw new IllegalArgumentException("Кількість адрес має бути позитивною");
        }
        if (Integer.bitCount(count) != 1) {
            throw new IllegalArgumentException(
                    "Кількість адрес не є степенем двійки, діапазон не зводиться до одного CIDR: " + count);
        }
        // Exact integer math: a power of two has its log2 equal to the trailing-zero count.
        int prefixLength = 32 - Integer.numberOfTrailingZeros(count);
        if (prefixLength < 0 || prefixLength > 32) {
            throw new IllegalArgumentException("Невірна кількість для IPv4: " + count);
        }
        // Валідуємо IP-адресу
        InetAddress inetAddress = InetAddress.getByName(ipAddress);
        return inetAddress.getHostAddress() + "/" + prefixLength;
    }

    /**
     * Converts an extended-file IPv4 delegation (start address plus an address
     * count) into the CIDR blocks that exactly cover it.
     *
     * <p>The count is not required to be a power of two — RIRs do delegate
     * ranges that need several blocks — so this returns one entry for the common
     * case and more when the range does not align.
     *
     * @param startAddress first address of the delegation
     * @param count        number of addresses in the delegation
     * @return the covering CIDR blocks, in ascending order
     */
    public static IPAddress[] ipv4RangeToCidrBlocks(String startAddress, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("Кількість адрес має бути позитивною: " + count);
        }
        IPAddress lower = new IPAddressString(startAddress).getAddress();
        if (lower == null) {
            throw new IllegalArgumentException("Невірна IPv4-адреса: " + startAddress);
        }
        IPAddress upper = lower.increment(count - 1L);
        IPAddress[] blocks = lower.toSequentialRange(upper).spanWithPrefixBlocks();
        if (blocks.length > 1) {
            log.debug("Delegation {}+{} spans {} CIDR blocks", startAddress, count, blocks.length);
        }
        return blocks;
    }

    /**
     * Форматує IPv6 адресу з префіксом (наприклад, 2a04:42c0::, 29 ->
     * 2a04:42c0::/29).
     *
     * @param ipAddress
     * @param prefixLength
     * @return
     * @throws java.net.UnknownHostException
     */
    public static String ipv6ToCidr(String ipAddress, int prefixLength) throws
            UnknownHostException {
        if (prefixLength < 0 || prefixLength > 128) {
            throw new IllegalArgumentException("Невірна довжина префікса для IPv6: " + prefixLength);
        }
        InetAddress inetAddress = InetAddress.getByName(ipAddress);
        return inetAddress.getHostAddress() + "/" + prefixLength;
    }

    /**
     * Перевіряє валідність ASN (повинен бути позитивним цілим числом).
     *
     * @param asn
     * @return
     */
    public static int validateAsn(String asn) {
        try {
            int value = Integer.parseInt(asn);
            if (value <= 0) {
                throw new IllegalArgumentException("ASN має бути позитивним");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Невірний ASN: " + asn, e);
        }
    }
}
