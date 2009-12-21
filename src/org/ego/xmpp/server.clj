(ns org.ego.xmpp.server
  (:gen-class)
  (:import [java.net InetAddress InetSocketAddress URL]
           [java.io 
            InputStreamReader OutputStreamWriter PushbackReader 
            ByteArrayInputStream Reader Writer OutputStream FileInputStream]
           [java.util.concurrent Executors]
           [java.security KeyStore Security]
           [java.security.cert X509Certificate]
           [javax.net.ssl SSLContext KeyManagerFactory TrustManager SSLEngine]
           [org.jboss.netty.bootstrap ServerBootstrap]
           [org.jboss.netty.channel 
            SimpleChannelHandler ChannelFutureListener ChannelHandlerContext ChannelStateEvent 
            ChildChannelStateEvent ExceptionEvent UpstreamMessageEvent DownstreamMessageEvent]
           [org.jboss.netty.channel.socket.nio NioServerSocketChannelFactory]
           [org.jboss.netty.handler.ssl SslHandler]
           [org.jboss.netty.handler.codec.string StringEncoder StringDecoder]
           [org.jboss.netty.handler.codec.frame Delimiters DelimiterBasedFrameDecoder]
           [org.jboss.netty.logging InternalLoggerFactory Log4JLoggerFactory])
  (:require [org.ego.core.common :as common])
  (:use [clojure.contrib.logging :only [log]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Channel 

(defstruct connection :ctx :channel)

(def #^{:private true
        :doc "Thread-local handle keeps netty conneciton information"}
     *connection*)

(def #^{:private true
        :doc "Thread-local handle keeps netty conneciton information"}
     connections (ref {}))

(defn- get-channel-handler
  "Generates a netty ChannelHandler given a shared ref to a channel-buffer;  a
   channel-buffer ref is necessary to pass characters from the netty messageReceived
   to the sax parser's InputStream"
  [fun] 
  (proxy [SimpleChannelHandler] []
    (messageReceived 
     [#^ChannelHandlerContext ctx #^UpstreamMessageEvent me]
     (let [msgs (binding [*connection* {:ctx ctx :event me}]
                  (fun :upstream (.. me getChannel getRemoteAddress) (. me getMessage)))]
      (if (not (empty? msgs))
         (doseq [message msgs]
           (. ctx (sendUpstream (new UpstreamMessageEvent 
                                     (. me getChannel) 
                                     message
                                     (.. me getChannel getRemoteAddress))))))))
    (writeRequested
     [#^ChannelHandlerContext ctx #^DownstreamMessageEvent me]
     (let [msg (binding [*connection* {:ctx ctx :event me}]
                  (fun :downstream (.. me getChannel getRemoteAddress) (. me getMessage)))]
       (if (not (nil? msg))
         (. ctx (sendDownstream (new DownstreamMessageEvent 
                                     (. me getChannel) 
                                     (. me getFuture)
                                     msg 
                                     (.. me getChannel getRemoteAddress)))))))
    (channelConnected 
     [#^ChannelHandlerContext ctx #^ChannelStateEvent event] 
     (do (dosync (alter connections assoc (.. event getChannel getRemoteAddress) (. event getChannel))) 
         (binding [*connection* {:ctx ctx :event event}]
           (fun :connect (.. event getChannel getRemoteAddress)))
         (. ctx (sendUpstream event))))
    (channelDisconnected
     [#^ChannelHandlerContext ctx #^ChannelStateEvent event] 
     (do (dosync (alter connections dissoc (.. event getChannel getRemoteAddress)))
         (binding [*connection* {:ctx ctx :event event}]
           (fun :disconnect (.. event getChannel getRemoteAddress)))
         (. ctx (sendUpstream event))))))

(def #^{:private true
        :doc "Base Handler intercepts and blocks uninteresting message types 
              from propagating up the pipeline;  passes channelConnected,
              channelDisconnected, handleUpstream, handleDownstream,
              messageReceived, unbindRequested, writeRequested, 
              setInterestOpsRequested"}
     base-server-handler
     (proxy [SimpleChannelHandler] []
       ; Block these message types
       (channelBound [#^ChannelHandlerContext ctx #^ChannelStateEvent cse] nil)
       (channelClosed [#^ChannelHandlerContext ctx #^ChannelStateEvent cse] nil)
       (channelInterestChanged [#^ChannelHandlerContext ctx #^ChannelStateEvent cse] nil)
       (channelOpen [#^ChannelHandlerContext ctx #^ChannelStateEvent cse] nil)
       (channelUnbound [#^ChannelHandlerContext ctx  #^ChannelStateEvent cse] nil)
       (childChannelClosed [#^ChannelHandlerContext ctx  #^ChildChannelStateEvent ccse] nil)
       (childChannelOpen [#^ChannelHandlerContext ctx #^ChildChannelStateEvent ccse] nil)
       (connectRequested [#^ChannelHandlerContext ctx #^ChannelStateEvent cse] nil) ; shouldnt see this one
       (exceptionCaught [#^ChannelHandlerContext ctx #^ExceptionEvent ee] (log :info "Handler error"  (. ee getCause)))
       ; Log and foward these
       (channelConnected 
        [#^ChannelHandlerContext ctx #^ChannelStateEvent cse] 
        (do (log :info (str "Channel " (.. cse getChannel getRemoteAddress) " Connected"))
            (. ctx (sendUpstream cse))))
       (channelDisconnected
        [#^ChannelHandlerContext ctx #^ChannelStateEvent cse] 
        (do (log :info (str "Channel " (.. cse getChannel getRemoteAddress) " Disconnected"))
          ;  (println (.. cse getChannel getRemoteAddress))
            (. ctx (sendUpstream cse))))))
        
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; SSL/TLS

(defn- get-ssl-context
  "Generate an SSLContext for a KeyStore"
  [path key-pass cert-pass]
  (doto (. SSLContext (getInstance "TLS"))
    (.init (.getKeyManagers (doto (. KeyManagerFactory (getInstance "SunX509"))
                              (.init (doto (KeyStore/getInstance "JKS")
                                       (.load (. ClassLoader (getSystemResourceAsStream path)) 
                                              (.toCharArray key-pass)))
                                     (.toCharArray cert-pass))))
           (into-array [(proxy [TrustManager] []
                          (getAcceptedIssuers [] (make-array X509Certificate 0))
                          (checkClientTrusted [x y] nil)
                          (checkServerTrusted [x y] nil))])
           nil)))

(defn- get-ssl-handler
  "Generate an SSLHandler"
  [path key-pass cert-pass]
  (SslHandler. (doto (.createSSLEngine (get-ssl-context path key-pass cert-pass))
                 (.setUseClientMode false))
               true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;
;;;; Public
;;;;
;;;; Provides functions to start and stop a server, and also implements netty
;;;; level functionality like TLS

(defn start-tls
  "Switch channel to TLS"
  []
  (let [handler (get-ssl-handler (common/properties :server:keystore)
                                 (common/properties :server:keypassword)
                                 (common/properties :server:certificatepassword))]
    (log :debug (str "Starting SSL Handshake "))
    (do (.. (:ctx *connection*) getPipeline (addFirst "ssl" handler))
        (.. handler 
            (handshake (. (:event *connection*) getChannel))
            (addListener (proxy [ChannelFutureListener] []
                           (operationComplete 
                            [future]
                            (log :debug (str "SSL Handshake finished : " (. future isSuccess))))))))))

(defn get-ip
  []
  (.. (:event *connection*) getChannel getRemoteAddress))

(defn close-channel
  []
  (do (.. (:event *connection*) getChannel close)
      nil))

(defn channel-write
  [ip msg]
  (. (@connections ip) (write msg)))

(defmacro defhandler
  "Create a netty ChannelHandler"
  [name doc-or-handle & handles]
  (if (string? doc-or-handle)
    `(def ~(with-meta name (assoc ^name :doc doc-or-handle))
          (create-handler ~@handles))
    `(def ~name
          (create-handler ~doc-or-handle ~@handles))))

(defn start-server
  "Create a new socket server bound to the port, adding the supplied funs 
   in order to the default pipeline"
  [port & funs]
  (do (. InternalLoggerFactory (setDefaultFactory (Log4JLoggerFactory.)))
      (let [bootstrap (ServerBootstrap. (NioServerSocketChannelFactory. (. Executors newCachedThreadPool)
                                                                        (. Executors newCachedThreadPool)))]
        (do (let [pipeline (. bootstrap getPipeline)]
              (doto pipeline
                (.addLast "decoder" (StringDecoder.))
                (.addLast "encoder" (StringEncoder.))
                (.addLast "ego-base" base-server-handler))
              (doseq [f funs]
                (. pipeline addLast (str "handler_" (.toString f)) (get-channel-handler f))))
            (. bootstrap (setOption "child.tcpNoDelay" true))
            (. bootstrap (setOption "child.keepAlive" true))
            (. bootstrap (bind (InetSocketAddress. port)))))))

(defn stop-server
  "Stop the supplied server"
  [server]
  (. server close))
