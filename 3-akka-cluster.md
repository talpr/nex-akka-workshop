Following all the new features we introduced in Nagger, its popularity has been sky-rocketing! That's great news for all
the business types, but our poor little server is starting to buckle under the load. Luckily we have a few spare servers
so all we have to do is run Nagger in a cluster and we'll be fine.

Task:
-----
Run Nagger in a cluster. Simple, no?

Solution Walk-through:
----------------------
* Add the appropriate configuration in `application.conf`:
    - Set the actor provider to "cluster"
    - Set the Netty hostname and port
    - Set the cluster seed nodes
* Check that everything still works.
* Instead of creating the `UserDetailsActor` directly, create a Singleton to manage and access it.
* Check that everything still works.
* Instead of creating `ChannelActor`s in the `ChannelManagerActor`, create a sharding region and communicate with the
    channels through it.
* Try it out again. This seems to work, but there is actually a bug. Can you spot it?
* We now have multiple `ChannelManagerActor`s and `ApiActor`s, but they each only know the actor on the same node as
    them. Solving this issue well is not straight-forward, so for now let's just change the `ChannelManagerActor` to a
    singleton. The `ChannelManagerActor` will now have to keep a set of `ApiActor` references.
    Note that this solution is problematic since, unlike the `UserDetailsActor`, the `ChannelManagerActor` is involved
    in all messages, creating a bottleneck. As a bonus, try solving this "properly". 
* You guessed it - check that everything works.
* If you haven't already, change the persistence plugin to use Cassandra.
* Try running several instances of your server. In order for it to work, they must each bind different ports for Akka,
    for the gRPC service, and for kit's gRPC ops service.