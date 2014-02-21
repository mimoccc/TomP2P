/*
 * Copyright 2009 Thomas Bocek
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package net.tomp2p.examples;

import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableSet;
import java.util.Random;
import java.util.TreeSet;

import net.tomp2p.futures.FutureGet;
import net.tomp2p.futures.FuturePut;
import net.tomp2p.p2p.Peer;
import net.tomp2p.p2p.PeerMaker;
import net.tomp2p.p2p.RequestP2PConfiguration;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.Number640;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.peers.PeerMap;
import net.tomp2p.storage.Data;

/**
 * Example of consistency and DHT attacks.
 * 
 * @author Thomas Bocek
 * 
 */
public final class ExampleConsistency {

    /**
     * Empty constructor.
     */
    private ExampleConsistency() {
    }

    private static final Random RND = new Random(5467656537115L);

    private static final RequestP2PConfiguration REQUEST_3 = new RequestP2PConfiguration(3, 10, 0);
    private static final RequestP2PConfiguration REQUEST_6 = new RequestP2PConfiguration(6, 10, 0);

    /**
     * Start the examples.
     * 
     * @param args
     *            Empty
     * @throws Exception .
     */
    public static void main(final String[] args) throws Exception {
        Peer master = null;
        try {
            final int peerNr = 100;
            final int port = 4001;
            Peer[] peers = ExampleUtils.createAndAttachNodes(peerNr, port);
            master = peers[0];
            ExampleUtils.bootstrap(peers);
            Number160 key1 = new Number160(RND);
            exampleConsistency(key1, peers);
            exampleAttack(key1, peers);
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            if (master != null) {
                master.shutdown();
            }
        }
    }

    /**
     * Starts the consistency examples. First we find which peers (0x480137e1846dca42894ca653b60f1446f7ca9cd4) are
     * responsible for a particular key (0x4bca44fd09461db1981e387e99e41e7d22d06894):
     * 
     * @param peers
     *            All the peers
     * @param key1
     *            The key to store the data
     * @throws IOException .
     * @throws ClassNotFoundException .
     */
    private static void exampleConsistency(final Number160 key1, final Peer[] peers) throws IOException,
            ClassNotFoundException {
        System.out.println("key is " + key1);
        // find close peers
        NavigableSet<PeerAddress> set = new TreeSet<PeerAddress>(PeerMap.createComparator(key1));
        for (Peer peer : peers) {
            set.add(peer.getPeerAddress());
        }
        System.out.println("closest peer " + set.first());
        
        final int peerStore1 = 22;
        peers[peerStore1].put(key1).setRequestP2PConfiguration(REQUEST_3).setData(new Data("Test 1")).start()
                .awaitUninterruptibly();
        // close peers go offline
        System.out.println("the following peers go offline");
        final int peerOffline1 = 67;
        final int peerOffline2 = 40;
        final int peerOffline3 = 39;
        System.out.println(peers[peerOffline1].getPeerAddress());
        System.out.println(peers[peerOffline2].getPeerAddress());
        System.out.println(peers[peerOffline3].getPeerAddress());
        peers[peerOffline1].shutdown();
        peers[peerOffline2].shutdown();
        peers[peerOffline3].shutdown();
        // now lets store something else with the same key
        final int peerGet = 33;
        FuturePut futurePut = peers[peerStore1].put(key1).setRequestP2PConfiguration(REQUEST_3)
                .setData(new Data("Test 2")).start();
        futurePut.awaitUninterruptibly();

        FutureGet futureGet = peers[peerGet].get(key1).setAll().start();
        futureGet.awaitUninterruptibly();
        System.out.println("peer[" + peerGet + "] got [" + futureGet.getData().object() + "] should be [Test 2]");
        // peer 11 and 8 joins again
        peers[peerOffline1] = new PeerMaker(peers[peerOffline1].getPeerID()).masterPeer(peers[0]).makeAndListen();
        peers[peerOffline2] = new PeerMaker(peers[peerOffline2].getPeerID()).masterPeer(peers[0]).makeAndListen();
        peers[peerOffline3] = new PeerMaker(peers[peerOffline3].getPeerID()).masterPeer(peers[0]).makeAndListen();
        peers[peerOffline1].bootstrap().setPeerAddress(peers[0].getPeerAddress()).start().awaitUninterruptibly();
        peers[peerOffline2].bootstrap().setPeerAddress(peers[0].getPeerAddress()).start().awaitUninterruptibly();
        peers[peerOffline3].bootstrap().setPeerAddress(peers[0].getPeerAddress()).start().awaitUninterruptibly();
        // load old data
        System.out.println("The 3 peers are now onlyne again, with the old data");
        Number640 key = new Number640(key1, Number160.ZERO, Number160.ZERO, Number160.ZERO);
        peers[peerOffline1].getPeerBean().storage()
                .put(key, new Data("Test 1"), null, false, false);
        peers[peerOffline2].getPeerBean().storage()
                .put(key, new Data("Test 1"), null, false, false);
        peers[peerOffline3].getPeerBean().storage()
                .put(key, new Data("Test 1"), null, false, false);
        // we got Test 1
        FutureGet futureGet2 = peers[0].get(key1).setRequestP2PConfiguration(REQUEST_3).setAll().start();
        futureGet2.awaitUninterruptibly();
        System.out.println("peer[0] got [" + futureGet2.getData().object() + "] should be [Test 2]");
        // we got Test 2!
        FutureGet futureGet3 = peers[peerGet].get(key1).setRequestP2PConfiguration(REQUEST_3).setAll().start();
        futureGet3.awaitUninterruptibly();
        System.out.println("peer[" + peerGet + "] got [" + futureGet3.getData().object() + "] should be [Test 2]");
    }

    /**
     * This example shows how to attack an DHT.
     * 
     * @param key1
     *            The key to attack
     * @param peers
     *            All the peers
     * @throws IOException .
     * @throws ClassNotFoundException .
     */
    private static void exampleAttack(final Number160 key1, final Peer[] peers) throws IOException,
            ClassNotFoundException {
        // lets attack!
        System.out.println("Lets ATTACK!");
        Peer mpeer1 = new PeerMaker(new Number160("0x4bca44fd09461db1981e387e99e41e7d22d06893"))
                .masterPeer(peers[0]).makeAndListen();
        Peer mpeer2 = new PeerMaker(new Number160("0x4bca44fd09461db1981e387e99e41e7d22d06894"))
                .masterPeer(peers[0]).makeAndListen();
        Peer mpeer3 = new PeerMaker(new Number160("0x4bca44fd09461db1981e387e99e41e7d22d06895"))
                .masterPeer(peers[0]).makeAndListen();
        mpeer1.bootstrap().setPeerAddress(peers[0].getPeerAddress()).start().awaitUninterruptibly();
        mpeer2.bootstrap().setPeerAddress(peers[0].getPeerAddress()).start().awaitUninterruptibly();
        // mpeer3.bootstrap().setPeerAddress(peers[0].getPeerAddress()).start().awaitUninterruptibly();
        // load old data
        Number640 key = new Number640(key1, Number160.ZERO, Number160.ZERO, Number160.ZERO);
        mpeer1.getPeerBean().storage()
                .put(key, new Data("attack, attack, attack!"), null, false, false);
        mpeer2.getPeerBean().storage()
                .put(key, new Data("attack, attack, attack!"), null, false, false);
        mpeer3.getPeerBean().storage()
                .put(key, new Data("attack, attack, attack!"), null, false, false);
        // we got attack!
        FutureGet futureGet = peers[0].get(key1).setAll().setRequestP2PConfiguration(REQUEST_3).start();
        futureGet.awaitUninterruptibly();
        System.out.println("peer[0] got " + futureGet.getData().object());
        for (Entry<PeerAddress, Map<Number640, Data>> entry : futureGet.getRawData().entrySet()) {
            System.out.print("got from (3)" + entry.getKey());
            System.out.println(entry.getValue());
        }
        // increase the replicas we fetch
        FutureGet futureGet1 = peers[0].get(key1).setAll().setRequestP2PConfiguration(REQUEST_6).start();
        futureGet1.awaitUninterruptibly();
        System.out.println("peer[0] got " + futureGet1.getData().object());

        // countermeasure - statistics, pick not closest, but random peer that has the data - freshness vs. load
        for (Entry<PeerAddress, Map<Number640, Data>> entry : futureGet1.getRawData().entrySet()) {
            System.out.print("got from (6)" + entry.getKey());
            System.out.println(entry.getValue());
        }
    }
}
