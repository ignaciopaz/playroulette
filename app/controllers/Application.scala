package controllers

import play.api._
import play.api.mvc._
import play.api.data.Forms._
import play.api.data._
import play.api.Play.current
import play.api.libs._
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import java.util.concurrent._
import scala.concurrent.stm._
import akka.util.duration._
import play.api.cache._
import play.api.libs.json._
import scala.collection.mutable._
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

object Application extends Controller {

    val eventSource = Enumeratee.map[String] { msg => "data: " + msg + "\n\n" }

    val number = new AtomicLong(0)

    def index() = Action {
        val uid = UUID.randomUUID().toString()
        val user = User.join( uid, "Anonymous-" + number.incrementAndGet(), "" )
        user.waiting( )
        val state = restartUser( user.id )
        Ok( views.html.index( uid, state ) )
    }

    def feed(id: String) = Action { implicit request =>
        User.users.get( id ).map { user =>
            Ok.feed( user.feedEnumerator.through( eventSource ) ).as( "text/event-stream" )
        }.getOrElse {
          NotFound( "User not found with id " + id )
        }
    }

    def websocket( id: String ) = WebSocket.async[Array[Byte]] { request =>
        User.users.get( id ).map { user =>
          Promise.pure( ( user.inputCameraIteratee, user.outputBroadcastEnumerator.getPatchCord() ) )
        }.getOrElse {
          Promise.pure( ( Iteratee.ignore, Enumerator.eof ) )
        }
    }

    def next( id: String ) = Action {
        User.findChat( id ).map { chat =>
            chat.stop()
            restartUser( chat.user1.id )
            restartUser( chat.user2.id )
            Ok( "" )
        }.getOrElse( NotFound( "Chat not found for user with id " + id ) )
    }

    def restartUser( id: String ) = {
        User.users.get( id ).map { user =>
            user.waiting()
            if ( User.waitingUsers.isEmpty ) {
                User.waitingUsers += user
                user.informWaiting()
                "waiting"
            } else {
                val otherChatter = User.waitingUsers.dequeue()
                val chat = Chat.register( user, otherChatter )
                chat.start()
                user.informNewChat( chat.id )
                otherChatter.informNewChat( chat.id )
                chat.id 
            }
        }.get
    }

    def userList() = Action {
        Ok( views.html.users( User.users.map { user => user._2 }.toList ) )
    }

    def user( id: String ) = Action {
        User.users.get( id ).map { user =>
            Ok( views.html.user( user ) )
        }.getOrElse {
          NotFound( "User not found with id " + id )
        }
    }

    def userCam( id: String ) = WebSocket.async[Array[Byte]] { request =>
        User.users.get( id ).map { user =>
          Promise.pure( ( Iteratee.ignore[Array[Byte]], user.outputUserBroadcastEnumerator.getPatchCord() ) )
        }.getOrElse {
          Promise.pure( ( Iteratee.ignore, Enumerator.eof ) )
        }
    }
}

case class User(id: String, name: String = "Anonymous", description: String = "") {

    private val optionnalConsumer: AtomicReference[Option[User]] = new AtomicReference(None)

    val inputCameraIteratee = Iteratee.foreach[Array[Byte]] ( _ match {
        case message : Array[Byte] => {
            optionnalConsumer.get().foreach { consumer =>
                consumer.pushFrame( message )
            }
            outputUserEnumerator.push( message )
        }
    }).mapDone({ in =>
        User.removeUser( id )
        outputEnumerator.close()
        outputUserEnumerator.close()
        outputBroadcastEnumerator.close()
        optionnalConsumer.get().foreach { consumer =>
            Application.restartUser( consumer.id )
        }
    })

    val feedEnumerator = Enumerator.imperative[String]() 

    private val outputUserEnumerator = Enumerator.imperative[Array[Byte]]() 
    val outputUserBroadcastEnumerator = Concurrent.hub[Array[Byte]]( outputUserEnumerator )

    private val outputEnumerator = Enumerator.imperative[Array[Byte]]() 
    val outputBroadcastEnumerator = Concurrent.hub[Array[Byte]]( outputEnumerator )

    def pushFrame(frame: Array[Byte]) = outputEnumerator.push( frame )

    def waiting() = optionnalConsumer.set( None )

    def connectToNewChatter( user: User ) = optionnalConsumer.set( Some( user ) )

    def informWaiting() = feedEnumerator.push( "waiting" )

    def informNewChat(id: String) = feedEnumerator.push( id )
}

object User {

    val users = HashMap.empty[String, User]

    val waitingUsers = new SynchronizedQueue[User]()

    def join( uid: String, name: String, desc: String ) = {
        val user = User( uid, name, desc )
        users.put( uid, user )
        user
    }

    def removeUser(id: String) = users.remove( id )

    def findChat( id: String ): Option[Chat] = {
        for ( chat <- Chat.chats.values ) {
            if( chat.user1.id.equals( id ) ) return Some( chat )
            if( chat.user2.id.equals( id ) ) return Some( chat )
        }
        None
    }
}

case class Chat( id: String, user1: User, user2: User ) {
    def start() {
        user1.connectToNewChatter( user2 )
        user2.connectToNewChatter( user1 )
    }
    def stop() = Chat.remove( id) 
}

object Chat {  

    val chats = HashMap.empty[String, Chat]

    def register( user1: User, user2: User ) = {
        val uid = UUID.randomUUID().toString()
        val chat = Chat( uid, user1, user2 )
        chats.put( uid, chat )
        chat
    }
    def remove( id: String ) = chats.remove( id ) 
}