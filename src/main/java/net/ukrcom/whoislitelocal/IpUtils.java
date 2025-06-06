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

import java.net.InetAddress;
import java.net.UnknownHostException;

public class IpUtils {

    /**
     * Перетворює IPv4 адресу та кількість адрес у CIDR-нотацію (наприклад,
     * 212.90.160.0, 8192 -> 212.90.160.0/19).
     *
     * @param ipAddress
     * @param count
     * @return
     * @throws java.net.UnknownHostException
     */
    public static String ipv4ToCidr(String ipAddress, int count) throws UnknownHostException {
        if (count <= 0) {
            throw new IllegalArgumentException("Кількість адрес має бути позитивною");
        }
        // Обчислюємо довжину префікса: log2(count) дає кількість бітів для хостів, тож 32 - log2(count) — це префікс
        int prefixLength = 32 - (int) Math.floor(Math.log(count) / Math.log(2));
        if (prefixLength < 0 || prefixLength > 32) {
            throw new IllegalArgumentException("Невірна кількість для IPv4: " + count);
        }
        // Валідуємо IP-адресу
        InetAddress inetAddress = InetAddress.getByName(ipAddress);
        return inetAddress.getHostAddress() + "/" + prefixLength;
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
    public static String ipv6ToCidr(String ipAddress, int prefixLength) throws UnknownHostException {
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
