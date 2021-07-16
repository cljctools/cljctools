## cljctools/peerdb
peer-to-peer database, best of orbitdb and datomic

## goal

- should sync data over IFPS pubsub, like orbitdb, but have datomic interface and design (time travel not necessary)
- no need for orbits eventlog/docstore/feed etc. - like in datomic, eventlog is tranasaction log , else is high level abstraction for querying
- database data must be stored on IPFS: so that peers could use DHT, torrent-like benefits of downloading data peer-to-peer (partial and full, all that database writes is ipfs data)
- the need 
  - find, the app to find files on torrent and ipfs networks, needs for millions of peers to contribute to join index of files (torrents and ipfs)
  - index is replicated on each user computer, and apps continuously update the index (each app always searches/listens to DHT for new files or to update seeder count, and would write to index)
  -  app on first install downloads existing index (e.g. 2million entries) from peers, and user can query it locally, a complete database, with full text serach, with sort by, count etc.
  - orbitdb currently (0.26.1) is slow for writing data (even just for thousands of files), and requires the whole data to be loaded into memory (improvements are outlined in their roadmap)
  - what is needed to fast update/delete thousands of entries easily, load into memeory only what's needed, query language, datomic/dgraph RDF-like nature (data is attributes, with multiple indexes for fast querying) and in general be like a normal high level db, efficient and fast