# whois-lite-local
Утиліта призначена для роботи з extended-файлами ripencc, arin, apnic, lacnic та afrinic, а також ripe.db, asnames та geolocations.

> Note: If manually applying schema.sql multiple times, you may encounter an "index already exists" error. This is expected behavior in SQLite, as it does not support `IF NOT EXISTS` for indexes. Delete the database file or ignore the error.

## Usage
```bash
java -jar WhoisLiteLocal-1.0.0.jar [options]
```

* --get-data, -gd: Download and process data from configured URLs (default behavior).
* --retrieve-aut-num, -ran <as-num>: Retrieve information about the aut-num object and related objects.
* --retrieve-as-set, -ras <as-set>: Retrieve information about the as-set object.
* --retrieve-mntner, -rm <mntnr>: Get information on the mntner object and its related objects.
* --retrieve-mnt-by, -rmb <mntnr>: Retrieve objects maintained by the specified maintainer.
* --retrieve-organisation, -ro <as-num>: Retrieve organisation information for the specified aut-num.
* --retrieve-route-origin, -rro <AS-num>: Retrieve information about route and route6 objects with the specified origin.
* --retrieve-network-origin, -rno <net-num>: Retrieve information about route and route6 objects for the specified network.
* --help, -h: Show help message.
