# whois-lite-local
Утиліта призначена для роботи з extended-файлами ripencc, arin, apnic, lacnic та afrinic, а також ripe.db, asnames та geolocations.

> Note: If manually applying schema.sql multiple times, you may encounter an "index already exists" error. This is expected behavior in SQLite, as it does not support `IF NOT EXISTS` for indexes. Delete the database file or ignore the error.