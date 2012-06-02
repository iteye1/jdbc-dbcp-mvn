package jdbc_pool.webapp;

import java.util.Properties;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.sql.DataSource;

import jdbc_pool.core.AppTokens;
import jdbc_pool.util.ObjectRegistry;

import org.apache.commons.dbcp.ConnectionFactory;
import org.apache.commons.dbcp.DriverManagerConnectionFactory;
import org.apache.commons.dbcp.PoolableConnectionFactory;
import org.apache.commons.dbcp.PoolingDataSource;
import org.apache.commons.pool.ObjectPool;
import org.apache.commons.pool.impl.GenericObjectPool;
import org.apache.log4j.Logger;

/*
This is part of the sample code for the article,
"App-Managed JDBC DataSources with commons-dbcp"
by Ethan McCallum.

http://today.java.net/2005/11/17/app-managed-datasources-with-commons-dbcp.html
*/
/**
 * Setup the pool, associate that with a DataSource, and store that DataSource
 * where other code can find it.
 * 
 * Obvious deficiencies:
 * 
 * 1/ ideally, a ServletContextListener should call some other, generic class to
 * do the real work. That way, the pool/DataSource would be available outside of
 * the container.
 * 
 * 2/ The database config should be passed in via context params, config file,
 * or something else. As this is demo code, there's no need...
 */
public class SetupDataSourceContextListener implements ServletContextListener {
  // - - - - - - - - - - - - - - - - - - - -
  private static Logger _log = Logger
      .getLogger(SetupDataSourceContextListener.class);

  // - - - - - - - - - - - - - - - - - - - -
  public void contextInitialized(ServletContextEvent sce) {
    try {
      _log.info("called for init");
      final Properties dbConfig = (Properties) ObjectRegistry.getInstance()
          .get(AppTokens.OBJECT_REGISTRY_JDBC_CONFIG);
      final Properties appConfig = (Properties) ObjectRegistry.getInstance()
          .get(AppTokens.OBJECT_REGISTRY_APP_CONFIG);
      final DataSource ds = setupDataSource(dbConfig);
      /*
      // if your container doesn't provide a writable JNDI tree, you can go the other route:
      // store the DataSource in the object registry.  This isn't as transparent as
      // using JNDI, though, because existing classes would have be updated to use the
      // object registry.  (Otherwise, you could just change the JNDI lookup name
      // and those classes would run the same as they did when using a container-provided
      // DataSource.)
      
      _log.info( "(storing DataSource under key \"" + AppTokens.OBJECT_REGISTRY_JDBC_DATASOURCE + "\")" ) ;
      ObjectRegistry.getInstance().put( AppTokens.OBJECT_REGISTRY_JDBC_DATASOURCE , ds ) ;
      */
      final String jndiLookupName = appConfig
          .getProperty(AppTokens.APP_CONFIG_DATASOURCE_JNDI_NAME);
      _log.info("Storing DataSource in JNDI, under \"" + jndiLookupName + "\")");
      bindObject(jndiLookupName, ds);
      _log.info("done with init");
      return;
    } catch (Throwable t) {
      final String message = "Error setting up data source: " + t.getMessage();
      _log.error(message, t);
      // this exception will cause Tomcat to disable the context
      throw (new RuntimeException(message, t));
    }
  } // contextInitialized()

  @SuppressWarnings("unchecked")
  public void contextDestroyed(ServletContextEvent sce) {
    ServletContext ctx = sce.getServletContext();
    ctx.log("SetupDataSourceContextListener: called for shutdown");
    try {
      final ObjectPool<Object> pool = (ObjectPool<Object>) ObjectRegistry
          .getInstance().get(AppTokens.OBJECT_REGISTRY_JDBC_POOL);
      if (null != pool) {
        pool.clear();
      }
    } catch (Throwable ignored) {
      _log.error("Failed to clear connection pool: " + ignored.getMessage(),
          ignored);
    }
    ctx.log("SetupDataSourceContextListener: completed shutdown");
    return;
  } // contextDestroyed()

  private DataSource setupDataSource(final Properties config) throws Exception {
    PoolingDataSource result = new PoolingDataSource();
    // instantiate the JDBC driver class.  Nothing new
    // here; this is JDBC 101
    Class
        .forName(config.getProperty(AppTokens.DB_CONFIG_JDBC_CLASSNAME).trim());
    // BEGIN: nicked from commons-dbcp examples
    /*
    NOTE: The basic structure of this code was nicked from the
    commons-jdbc example in "ManualPoolingDataSourceExample.java"...
    I've added/clarified comments and adopted my coding style, that's
    all
    -qm 2004/09/15
    */
    /*
    As the name says, this is a generic pool; it returns
    basic Object-class objects.
    */
    final GenericObjectPool<Object> pool = new GenericObjectPool<Object>(null);
    pool.setWhenExhaustedAction(GenericObjectPool.WHEN_EXHAUSTED_BLOCK);
    /*
    ConnectionFactory creates connections on behalf of the pool.
    Here, we use the DriverManagerConnectionFactory because that essentially
    uses DriverManager as the source of connections.
    */
    ConnectionFactory factory = new DriverManagerConnectionFactory(config
        .getProperty(AppTokens.DB_CONFIG_JDBC_URL).trim(), config.getProperty(
        AppTokens.DB_CONFIG_JDBC_LOGIN).trim(), config.getProperty(
        AppTokens.DB_CONFIG_JDBC_PASSWORD).trim());
    /*
    Puts pool-specific wrappers on factory connections.  For clarification:
    "[PoolableConnection]Factory," not "Poolable[ConnectionFactory]."
    */
    //PoolableConnectionFactory pcf = 
    new PoolableConnectionFactory(factory, // ConnectionFactory
        pool, // ObjectPool
        null, // KeyedObjectPoolFactory
        null, // String (validation query)
        false, // boolean (default to read-only?)
        true // boolean (default to auto-commit statements?)
    );
    /*
    initialize the pool to 10 connections
    */
    _log.info("Pool defaults to " + pool.getNumActive() + " active/"
        + pool.getNumIdle() + " idle connections.");
    for (int ix = 0; ix < 10; ++ix) {
      pool.addObject();
    }
    // END: nicked from commons-dbcp examples
    _log.info("Pool now has " + pool.getNumActive() + " active/"
        + pool.getNumIdle() + " idle connections.");
    /*
    All of this is wrapped in a DataSource, which client code should
    already know how to handle (since it's the same class of object
    they'd fetch via the container's JNDI tree
    */
    result.setPool(pool);
    // store the pool, so we can get to it later
    ObjectRegistry.getInstance().put(AppTokens.OBJECT_REGISTRY_JDBC_POOL, pool);
    return (result);
  } // setupDataSource()

  private void bindObject(final String fullPath, final Object toBind)
      throws Exception {
    _log.info("attempting to bind object " + toBind + " to context path \""
        + fullPath + "\"");
    Context currentContext = new InitialContext();
    final String name = currentContext.composeName(fullPath,
        currentContext.getNameInNamespace());
    final String[] components = name.split("/");
    // the last item in the array refers to the object itself.
    // we don't want to create a (sub)Context for that; we
    // want to bind the object to it
    final int stop = components.length - 1;
    for (int ix = 0; ix < stop; ++ix) {
      final String nextPath = components[ix];
      _log.debug("Looking up subcontext named \"" + nextPath + "\" in context "
          + currentContext);
      try {
        currentContext = (Context) currentContext.lookup(nextPath);
        _log.debug("found");
      } catch (final NameNotFoundException ignored) {
        _log.debug("not found; creating subcontext");
        currentContext = currentContext.createSubcontext(nextPath);
        _log.debug("done");
      }
    }
    // by this point, we've built up the entire context path leading up
    // to the desired bind point... so we can bind the object itself
    _log.info("binding to " + currentContext);
    currentContext.bind(components[stop], toBind);
    return;
  } // createContextDepth()
} // public class SetupDataSourceContextListener
