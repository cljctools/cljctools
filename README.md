# bittorrent-dht-crawl-nodejs
[extracted for reference] program that finds torrents using dht only

code is no longer used directly, because will be translated to jvm

- last run was 1h
- with 6881 port open on router (for announce/get_peers dht messages to come)
- ~40000 unique infohashes discovered: ~20000 from sample_infohases, ~20000 from "listening" to announce/get_peers
- ~24000 infohashes processed to find metadata, ~60% CPU, always exactly 80 metadatas in process
- ~8000 unique torrents found