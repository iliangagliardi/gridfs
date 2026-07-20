# Horizontal Scaling with MongoDB Sharding

Sharding is how MongoDB scales a single logical collection across many
machines. A sharded cluster has three moving parts: the shards themselves,
which hold the data; the config servers, which hold the cluster metadata; and
the mongos routers, which sit in front and send each operation to the right
place.

## Choosing a shard key

The shard key is the single most consequential decision in a sharded
deployment. A good key has high cardinality, low frequency, and does not
increase monotonically. A monotonically increasing key such as a timestamp or
an ObjectId funnels every new write into the same chunk, producing the classic
hot shard problem where one machine absorbs the entire insert workload while
the rest of the cluster idles.

Hashed sharding solves the hot shard problem by distributing writes evenly,
but it destroys range locality: a query for a contiguous span of values becomes
a scatter gather across every shard. Ranged sharding preserves locality at the
cost of requiring a genuinely well distributed key.

## Chunks, balancing, and jumbo chunks

Data is divided into chunks of 128 MB by default. The balancer migrates chunks
between shards in the background to keep the distribution even. A chunk that
cannot be split because every document in it shares the same shard key value is
marked jumbo, and jumbo chunks are the usual symptom of a low cardinality key.

## Sharding GridFS

GridFS collections shard well. Shard fs.chunks on the compound key
{ files_id: 1, n: 1 }, or on { files_id: "hashed" } when you would rather
distribute whole files evenly and never split a single file across shards.
Shard fs.files on _id. Because every chunk read is keyed by files_id, reads
are targeted rather than scattered, which is exactly the property you want for
range requests against large media objects.
