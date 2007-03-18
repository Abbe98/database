/**

The Notice below must appear in each file of the Source Code of any
copy you distribute of the Licensed Product.  Contributors to any
Modifications may add their own copyright notices to identify their
own contributions.

License:

The contents of this file are subject to the CognitiveWeb Open Source
License Version 1.1 (the License).  You may not copy or use this file,
in either source code or executable form, except in compliance with
the License.  You may obtain a copy of the License from

  http://www.CognitiveWeb.org/legal/license/

Software distributed under the License is distributed on an AS IS
basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.  See
the License for the specific language governing rights and limitations
under the License.

Copyrights:

Portions created by or assigned to CognitiveWeb are Copyright
(c) 2003-2003 CognitiveWeb.  All Rights Reserved.  Contact
information for CognitiveWeb is available at

  http://www.CognitiveWeb.org

Portions Copyright (c) 2002-2003 Bryan Thompson.

Acknowledgements:

Special thanks to the developers of the Jabber Open Source License 1.0
(JOSL), from which this License was derived.  This License contains
terms that differ from JOSL.

Special thanks to the CognitiveWeb Open Source Contributors for their
suggestions and support of the Cognitive Web.

Modifications:

*/
/*
 * Created on Jun 19, 2006
 */
package org.CognitiveWeb.bigdata.jini;

import java.io.IOException;
import java.rmi.Remote;
import java.rmi.RemoteException;

import net.jini.admin.JoinAdmin;
import net.jini.core.entry.Entry;
import net.jini.core.lease.Lease;
import net.jini.core.lookup.ServiceID;
import net.jini.core.lookup.ServiceItem;
import net.jini.core.lookup.ServiceRegistrar;
import net.jini.core.lookup.ServiceRegistration;
import net.jini.discovery.DiscoveryEvent;
import net.jini.discovery.DiscoveryListener;
import net.jini.discovery.LookupDiscovery;
import net.jini.export.Exporter;
import net.jini.export.ProxyAccessor;
import net.jini.id.Uuid;
import net.jini.id.UuidFactory;
import net.jini.jeri.BasicILFactory;
import net.jini.jeri.BasicJeriExporter;
import net.jini.jeri.tcp.TcpServerEndpoint;
import net.jini.lease.LeaseListener;
import net.jini.lease.LeaseRenewalEvent;
import net.jini.lease.LeaseRenewalManager;
import net.jini.lookup.entry.Address;
import net.jini.lookup.entry.Comment;
import net.jini.lookup.entry.Location;
import net.jini.lookup.entry.Name;
import net.jini.lookup.entry.ServiceInfo;
import net.jini.lookup.entry.ServiceType;
import net.jini.lookup.entry.Status;
import net.jini.lookup.entry.StatusType;

import org.apache.log4j.Logger;

/**
 * Launches a server used by the test. The server is launched in a separate
 * thread that will die after a timeout, taking the server with it. The server
 * exposes some methods for testing, notably a method to test remote method
 * invocation and one to shutdown the server.
 * 
 * @todo Look into the manager classes for service joins, discovery, etc. The
 *       code in this class can probably be simplified drammatically.
 * 
 * @version $Id$
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson
 *         </a>
 */
public class TestServer implements DiscoveryListener, LeaseListener
{
    
    public static Logger log = Logger.getLogger(TestServer.class);
    
    private ServiceID serviceID; 
    private ServiceItem item;
    private ServiceRegistration reg;
    final private LeaseRenewalManager leaseManager = new LeaseRenewalManager();

    /**
     * Server startup performs asynchronous multicast lookup discovery. The
     * {@link #discovered(DiscoveryEvent)}method is invoked asynchronously
     * to register {@link TestServerImpl}instances.
     */
    public TestServer() {

        /*
         * Generate a ServiceID ourselves. This makes it easier to register the
         * same service against multiple lookup services.
         * 
         * @todo If you want to restart (or re-register) the same service, then
         * you need to read the serviceID from some persistent location. If you
         * are using activation, then the service can be remotely started using
         * its serviceID which takes that responsibility out of your hands. When
         * using activation, you will only create a serviceID once when you
         * install the service onto some component and activition takes
         * responsiblity for starting the service on demand.
         */
        Uuid uuid = UuidFactory.generate();
        serviceID = new ServiceID(uuid.getMostSignificantBits(),
                uuid.getLeastSignificantBits());

        try {

            LookupDiscovery discover = new LookupDiscovery(
                    LookupDiscovery.ALL_GROUPS);
            
            discover.addDiscoveryListener(this);
            
        } catch (IOException ex) {
            
            throw new RuntimeException(ex);
            
        }

    }

    /**
     * Log a message and register the {@link TestServerImpl}. Events are
     * aggregated but we can still receive multiple events depending on the
     * latency between discovery of various service registrars. Since the
     * multicast protocol was used to discover service registrars, we can wind
     * up registering with more than one registrar.
     * 
     * @todo If we do not receive this message after some timeout then the
     *       server should log an error (in main()) and exit with a non-zero
     *       status code. This means that the service needs to expose an
     *       indicator of whether or not it has been registered.
     * 
     * @todo Modify service proxy to use RMI. The client stub should be
     *       downloaded from the codebase and should know how to commuicate with
     *       the discovered service, e.g., using RMI or a custom NIO protocol.
     * 
     * @todo This should be a fast operation and should not make remote calls.
     *       Service registration therefore needs to happen in another thread so
     *       that the service registrar can continue about its business.
     */
    public void discovered(DiscoveryEvent evt) {

        /*
         * At this point we have discovered one or more lookup services.
         */

        registerService( evt.getRegistrars() );
        
    }

    /**
     * Registers a service proxy with each identified registrar. The
     * registration happens in another thread to keep the latency down for the
     * {@link DiscoveryListener#discovered(net.jini.discovery.DiscoveryEvent)}
     * event.
     * 
     * @param registrars
     *            One or more service registrars.
     */
    private void registerService( final ServiceRegistrar[] registrars ) {

        log.info("Discovered "+registrars.length+" service registrars");

        new Thread("Register Service") {
            public void run() {
                // the service 
                Object impl = new TestServerImpl().getProxy();
                // Create an information item about a service
                item = new ServiceItem(serviceID, impl, new Entry[] {
                        new Comment("Test service(comment)"), // human facing comment.
                        new Name("Test service(name)"), // human facing name.
                        new MyServiceType("Test service (display name)",
                                "Test Service (short description)"), // service type
                        new MyStatus(StatusType.NORMAL),
                        new Location("floor","room","building"),
                        new Address("street", "organization", "organizationalUnit",
                                "locality", "stateOrProvince", "postalCode",
                                "country"), 
                        new ServiceInfo("bigdata", // product or package name
                                "SYSTAP,LLC", // manufacturer
                                "SYSTAP,LLC", // vendor
                                "0.1-beta", // version
                                "bigdata", // model
                                "serial#" // serialNumber
                        ) });

                for (int i = 0; i < registrars.length; i++) {
                    /*
                     * Register a service. The service requests a "long" lease using
                     * Lease.FOREVER. The registrar will decide on the actual length
                     * of the lease.
                     */
                    ServiceRegistrar registrar = registrars[i];
                    while (reg == null) {
                        try {
                            // Register the service.
                            reg = registrar.register(item, Lease.FOREVER);
                            /*
                             * Setup automatic lease renewal.
                             * 
                             * Note: A single lease manager can handle multiple services
                             * and the lease of a given service on multiple service
                             * registrars. It knows which lease is about to expire and
                             * preemptively extends the lease on the behalf of the
                             * registered service. It will notify the LeaseListener iff
                             * it is unable to renew a lease.
                             */
                            log.info("lease will expire in "
                                    + (reg.getLease().getExpiration() - System
                                            .currentTimeMillis()) + "ms");
                            leaseManager
                                    .renewUntil(reg.getLease(), Lease.FOREVER, TestServer.this);
                            /*
                             * Service has been registered and lease renewal is
                             * operating.
                             */
                            break;
                        } catch (RemoteException ex) {
                            // retry until successful.
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException ex2) {
                            }
                        }
                    }
                }
           }
        }.start()
        ;
        
            
    }
    
    /**
     * Log a message.
     */
    public void discarded(DiscoveryEvent arg0) {

        log.info("");
        
    }

    /**
     * Note: This is only invoked if the automatic lease renewal by the
     * lease manager is denied by the service registrar. In that case we
     * should probably update the Status to indicate an error condition.
     */
    public void notify(LeaseRenewalEvent event) {

        log.error("Lease could not be renewed: " + event);
        
    }
    
    /**
     * Launch the server in a separate thread.
     */
    public static void launchServer() {
        new Thread("launchServer") {
            public void run() {
                TestServer.main(new String[] {});
            }
        }.start();
    }

    /**
     * Run the server. It will die after a timeout.
     * 
     * @param args
     *            Ignored.
     */
    public static void main(String[] args) {
        final long lifespan = 3 * 60 * 1000; // life span in seconds.
        log.info("Will start test server.");
        TestServer testServer = new TestServer();
        log.info("Started test server.");
        try {
            Thread.sleep(lifespan);
        }
        catch( InterruptedException ex ) {
            log.warn(ex);
        }
        /*
         * @todo This forces a hard reference to remain for the test server.
         */
        log.info("Server will die: "+testServer);
    }
    
    /**
     * {@link Status} is abstract so a service needs to provide their own
     * concrete implementation.
     * 
     * @version $Id$
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     */
    public static class MyStatus extends Status {

        /**
         * 
         */
        private static final long serialVersionUID = 3431522046169284463L;
        
        /**
         * Deserialization constructor (required).
         */
        public MyStatus(){}
        
        public MyStatus(StatusType statusType) {

            /*
             * Note: This just sets the read/write public [severity] field on
             * the super class.
             */
            super(statusType);
            
        }
        
    }
    
    /**
     * {@link ServiceType} is abstract so a service basically needs to provide
     * their own concrete implementation. This class does not support icons
     * (always returns null for {@link ServiceType#getIcon(int)}. See
     * {@link java.beans.BeanInfo} for how to interpret and support the
     * getIcon() method.
     * 
     * @version $Id$
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson
     *         </a>
     */
    public static class MyServiceType extends ServiceType
    {

        /**
         * 
         */
        private static final long serialVersionUID = -2088608425852657477L;
        
        public String displayName;
        public String shortDescription;
        
        /**
         * Deserialization constructor (required).
         */
        public MyServiceType() {}

        public MyServiceType(String displayName, String shortDescription) {
            this.displayName = displayName;
            this.shortDescription = shortDescription;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public String getShortDescription() {
            return shortDescription;
        }
        
    }

    /**
     * The remote service implementation object. This implements the
     * {@link Remote} interface and uses JERI to create a proxy for the remote
     * object and configure and manage the protocol for communications between
     * the client (service proxy) and the remote object (the service
     * implementation).
     * <p>
     * Note: You have to implement {@link JoinAdmin} in order to show up as an
     * administerable service (blue folder) in the jini Service Browser.
     * 
     * @version $Id$
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson
     *         </a>
     */
    public static class TestServerImpl implements ProxyAccessor, ITestService/*, Serializable*/
    {

        /**
         * Note: Mark the {@link Logger} as transient since we do NOT need to
         * serialize its state.
         * <p>
         * 
         * @todo When the service uses a proxy and a remote communications
         *       protocol, the service instance is remote and this object is not
         *       instantiated on the client (test this hypothesis by removing
         *       log4j from the codebase).
         */
        public static final transient Logger log = Logger.getLogger(TestServerImpl.class);

        // Note required when using a service proxy.  the Exporter handles the proxy generation.
//        /**
//         * 
//         */
//        private static final long serialVersionUID = -920558820563934297L;

        /**
         * The location of the JERI configuration file.
         * 
         * @todo provide a command line option to override the file location.
         */
        private transient static String CONFIG_FILE = "jeri/jeri.config";

        private ITestService proxy;
        
//        /**
//         * 
//         * @param args
//         * @throws Exception
//         * 
//         * @see http://jan.newmarch.name/java/jini/tutorial/Jeri.xml for an
//         *      explanation of what is going on here.
//         */
//        public static void main(String[] args) throws Exception {
//
//            String[] configArgs = new String[] { CONFIG_FILE };
//
//            // get the configuration (by default a FileConfiguration)
//            Configuration config = ConfigurationProvider
//                    .getInstance(configArgs);
//            System.out.println("Configuration: " + config.toString());
//            
//            // and use the configuration to construct an exporter
//            Exporter exporter = (Exporter) config.getEntry(
//                    //
//                    TestServerImpl.class.getName(), // component
//                    "exporter", // name
//                    Exporter.class, // type (of the return object)
//                    new BasicJeriExporter(TcpServerEndpoint.getInstance(0),
//                            new BasicILFactory()) /* default */,//
//                    Configuration.NO_DATA // data.
//                    );
//
//            // export an object of this class
//            Remote proxy = exporter.export(new TestServerImpl());
//            System.out.println("Proxy is " + proxy.toString());
//
//            // now unexport it once finished
//            exporter.unexport(true);
//
//        } 

        /*
         * @todo presumably service shutdown should clear the proxy reference
         * once the service has been removed from the registry.
         */
        public Object getProxy() {

            return proxy;
        
        }        
        
        /**
         * Service constructor.
         * 
         * @todo can the constructor accept arguments?
         */
        public TestServerImpl() /*@todo throws RemoteException ?*/ {

            /*
             * @todo when this is added the test will succeed the first time but
             * thereafter will fail with a connection refused to the end point
             * for the _expired_ service. While that exception makes sense in
             * that the expired service should not be connectable, I do not
             * understand why adding or dropping this line has any influence.
             * Perhaps the problem is that it changes GC behavior and the prior
             * test service is otherwise NOT expired? Look into what this
             * service is doing to shutdown after the test and what is should
             * be doing.
             * 
             * So - the test does NOT attempt to close down the service - I was
             * deliberately leaving it open so that I could explore the service
             * in the Service Browser.  The issue may be that this replaces the
             * SecurityManager after one was already installed in the VM by the
             * test itself! 
             */
//            System.setSecurityManager(new SecurityManager());

            try {

                // @todo work out use of a configuration file.
//                String[] configArgs = new String[] { CONFIG_FILE };
//
//                // get the configuration (by default a FileConfiguration)
//                Configuration config = ConfigurationProvider
//                        .getInstance(configArgs);
//                System.out.println("Configuration: " + config.toString());
//                
//                // and use the configuration to construct an exporter
//                Exporter exporter = (Exporter) config.getEntry(
//                        //
//                        TestServerImpl.class.getName(), // component
//                        "exporter", // name
//                        Exporter.class, // type (of the return object)
//                        new BasicJeriExporter(TcpServerEndpoint.getInstance(0),
//                                new BasicILFactory()) /* default */,//
//                        Configuration.NO_DATA // data.
//                        );

                // hardwired jeri exporter using TCP.
                Exporter exporter = new BasicJeriExporter(TcpServerEndpoint
                        .getInstance(0), new BasicILFactory());

                // export an object of this class
                proxy = (ITestService) exporter.export(this);
                log.info("Proxy is " + proxy + "(" + proxy.getClass() + ")");

            } catch(Exception ex) {
                // @todo should we retry the operation?
                log.error(ex);
                throw new RuntimeException(ex);
            }

            // @todo who will unexport the proxy?
            
//                // now unexport it once finished
//                exporter.unexport(true);
                
//            System.err.println("Created: "+this);
            log.info("Created: "+this);
        }

        public void invoke() {
//            System.err.println("invoked: "+this);
            log.info("invoked: "+this);
        }

    }

}
