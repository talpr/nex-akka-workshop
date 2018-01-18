Throughout this course we will be building a chat program (like Slack, only better!). Today we'll start with the (gRPC)
interface and the basic actors that will operate behind the scenes - leaving the hard problems of persistence,
clustering, etc. for later.

This way we will get to know the ideas behind the actor model and Akka's implementation of it, and also Kit, Traiana's
micro-service infrastructure.

Task:
-----
Create a server using the provided Protocol Buffers API (see `nagger.proto`), that supports the following:

* Clients can `Register` new users. Successful registration should also log the user into the system. (No need to
  persist the registered user information, for now)
* Clients can `Login`. Successful logins should return a token to the client, that it can use in further communications
  with the server.
* Once logged in, clients can use the `Listen` API to open a stream for notifications from the server. They can also use
  the `JoinLeave` and `SendMessage` APIs to join/leave channels and post messages to their "current" channel.
* Logged-in clients can also use the following APIs:
    1. `JoinLeave` - Join or leave a channel.
    2. `SendMessage` - Post a message to a (joined) channel. This should result in the message being sent from the
        server to _all_ clients that have joined that channel and are currently "listening".

To that end, start by generating the gRpc server interface (using the Gradle `generateProto` task). Next, start
creating the actors that implement all the logic and interact with the gRpc code.

Walk-through:
-------------
* Set up Spring Boot and the gRpc server:
    - Write a `Nagger` "main class" that starts the Spring application and annotate it with `@SpringBootApplication`.
    - Write a `NaggerService` "stub" class that extends the generated `NaggerService` class and `BindableService`.
      Annotate it with `@Service` and `@GrpcService`, and implement its `bindService` method.
    - Run your server. Try to connect to it with the provided client.
* Create an `ApiActor` class that will handle the commands coming in from the Service. For commands that have a token,
  the actor should first validate the token (with the `LoginActor`) and obtain the client's nickname before "handling"
  the command. It should also maintain a mapping from nicknames to `StreamObserver`s for all connected clients, so that
  it can notify them when there are new messages.
* In your `NaggerService`, create an `ActorSystem` and an `ApiActor`, and forward all gRPC requests to the actor. You
  can use Akka's `ask` pattern to easily obtain a `Future` to return, 
* Write a `UserDetailsActor` class, that will handle user registration, and checking clients' user-name and password.
* Write a `LoginActor` class that handles login requests. It should delegate checking the credentials to the
  `UserDetailsActor`, and in case of a successful login generate a session token for the client. The returned tokens
  should be unpredictable, so that hackers can't simply forge them and post messages in your name.
* Connect the `ApiActor` to the `UserDetailsActor` and `LoginActor`, so users can now register and login to the system.
* Test that the client can now log in to your server.
* Write a `ChannelActor` that represents a specific channel. It should manage a list of clients that have joined the
  channel and when one of them posts a message should publish it to the entire list.
* Write a `ChannelManagerActor` that acts as a router between the `ApiActor` and the `ChannelActor`s. The actor should:
    - Handle commands coming in from the service by making sure the appropriate `ChannelActor` exists (or create it if
      it doesn't), and forwarding the command to it.
    - Handle notifications coming from the 'ChannelActor' and pass them back to the `ApiActor`.
* Connect the `ApiActor` to the `ChannelManagerActor`.
* One final test to see that everything works!

Bonus:
------
* Unit tests: Use the Akka Testkit to test your actors
* Add support for listing clients connected to the server, clients connected to a channel, and list of channels.
* Notify everyone in a channel when a user joins/leaves the channel.
* Add support for "direct messages" - messages from one client to another that don't belong to a channel.