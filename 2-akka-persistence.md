We now have a basic chat program, but for a "real" application we usually need to manage persistent data. Today we will
do just that, using the akka-persistence module to save our data to Cassandra. Along the way we'll explore the idea of
event-sourcing and see how it differs from saving state in a DB like we're used to. We will also learn about Akka's
configuration library and see how we can use it to configure Akka or for our own custom configuration.
 
Task:
-----
Add some necessary new features to your chat application:
* Add user management. The server should manage and persist usernames, passwords and nicknames.
* Save the channels users are subscribed to, to avoid the need to rejoin the same channels every time a user logs in.
* Save the channel's history and send it to clients when they join the channel.

Solution Walk-through:
----------------------
* Add the appropriate configuration in `application.conf`:
    - Set both the journal and snapshot-store to use the Cassandra plugin (you can start with the in-memory persistence
        plugin for now if it's easier, just remember it doesn't actually persist anything).
    - Configure the journal and snapshot-store with the appropriate contact points(localhost), port(9042) and keyspace.
    - Set the journal and snapshot-store to auto-start.
* Run the Cassandra Docker (or use the provided `docker-compose`).
* Run the server to allow the persistence plugin to create the keyspace in Cassandra.
* Make your `UserDetailsActor` persistent and add the user management functionality:
    - Keep user information in memory.
    - When a new user registers, make sure it doesn't exist already. If it doesn't, persist the data and update state.
    - When a user tries to log-in, check the state in-memory.
* Make your `ChannelActor` persistent, and persist users' join/leave events.
* Make your `ChannelManagerActor` persist channel creation events, and create the channels when recovering.
* Make your `ChannelActor` persist all messages sent to the channel. Whenever a user joins the channel for the first
    time replay the channel history for them.