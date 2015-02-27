package org.pentaho.marketplace.domain.services;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.Predicate;

import org.pentaho.di.core.Const;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.plugins.KettleURLClassLoader;
import org.pentaho.di.core.plugins.PluginInterface;
import org.pentaho.di.core.plugins.PluginRegistry;
import org.pentaho.di.core.plugins.PluginTypeInterface;

import org.pentaho.marketplace.domain.model.entities.MarketEntryType;
import org.pentaho.marketplace.domain.model.entities.interfaces.IPlugin;
import org.pentaho.marketplace.domain.model.entities.interfaces.IPluginVersion;
import org.pentaho.marketplace.domain.model.factories.interfaces.IDomainStatusMessageFactory;
import org.pentaho.marketplace.domain.model.factories.interfaces.IPluginVersionFactory;
import org.pentaho.marketplace.domain.model.factories.interfaces.IVersionDataFactory;
import org.pentaho.marketplace.domain.services.interfaces.IRemotePluginProvider;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


public class KettlePluginService extends BasePluginService {

  // region Properties
  private static final String BASE_PLUGINS_FOLDER_NAME = "plugins";

  // TODO turn into explicit dependency
  private PluginRegistry getPluginRegistry() {
    return PluginRegistry.getInstance();
  }

  public IPluginVersionFactory getPluginVersionFactory() {
    return this.pluginVersionFactory;
  }
  public KettlePluginService setPluginVersionFactory( IPluginVersionFactory factory ) {
    this.pluginVersionFactory = factory;
    return this;
  }
  private IPluginVersionFactory pluginVersionFactory;
  // endregion

  // region Constructor
  public KettlePluginService( IRemotePluginProvider metadataPluginsProvider,
                              IVersionDataFactory versionDataFactory,
                              IDomainStatusMessageFactory domainStatusMessageFactory,
                              IPluginVersionFactory pluginVersionFactory ) {
    super( metadataPluginsProvider, versionDataFactory, domainStatusMessageFactory );

    this.setPluginVersionFactory( pluginVersionFactory );
  }
  // endregion

  @Override
  protected boolean hasMarketplacePermission() {
    return true;
  }

  @Override
  protected void unloadPlugin( String pluginId ) {
    PluginInterface pluginObj = this.getPluginObject( pluginId );
    if ( pluginObj == null ) {
      this.getLogger().debug( "Plugin " + pluginId + " not found. Skipping unload." );
      return;
    }

    try {
      ClassLoader classLoader = this.getPluginRegistry().getClassLoader( pluginObj );
      if ( classLoader instanceof KettleURLClassLoader ) {
        ( (KettleURLClassLoader) classLoader ).closeClassLoader();
      }
    } catch ( KettleException e ) {
      this.getLogger().error( "Failed unloading plugin " + pluginId, e );
    }
  }

  @Override
  protected IPluginVersion getInstalledPluginVersion( IPlugin plugin ) {
    String pluginFolder = buildPluginsFolderPath( plugin ) + File.separator + plugin.getId();
    File pluginFolderFile = new File( pluginFolder );

    if ( !pluginFolderFile.exists() ) {
      return null;
    }

    String versionPath = pluginFolder + File.separator + "version.xml";
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    FileReader reader = null;
    try {
      File file = new File( versionPath );
      if ( !file.exists() ) {
        return null;
      }
      DocumentBuilder db = dbf.newDocumentBuilder();
      reader = new FileReader( versionPath );
      Document dom = db.parse( new InputSource( reader ) );
      NodeList versionElements = dom.getElementsByTagName( "version" );
      if ( versionElements.getLength() >= 1 ) {
        Element versionElement = (Element) versionElements.item( 0 );

        IPluginVersion version = this.getPluginVersionFactory().create();
        version.setBuildId( versionElement.getAttribute( "buildId" ) );
        version.setBranch( versionElement.getAttribute( "branch" ) );
        version.setVersion( versionElement.getTextContent() );

        return version;
      }

    } catch ( Exception e ) {
      e.printStackTrace();
    } finally {
      try {
        if ( reader != null ) {
          reader.close();
        }
      } catch ( Exception e ) {
        e.printStackTrace();
      }
    }

    return null;
  }

  /***
   * Goes to every folder where market entries may be installed and assumes each sub-folder is a market entry id
   * @return
   */
  @Override
  protected Collection<String> getInstalledPluginIds() {
    Collection<String> pluginIds = new HashSet<>();

    for( MarketEntryType type : MarketEntryType.values() ) {
      String pluginTypeFolderName = this.getInstallationSubfolder( type );
      pluginTypeFolderName = BASE_PLUGINS_FOLDER_NAME + ( pluginTypeFolderName == null ? "" : Const.FILE_SEPARATOR + pluginTypeFolderName );
      File pluginTypeFolder = new File( pluginTypeFolderName );
      File[] files = pluginTypeFolder.listFiles();
      if ( files != null ) {
        for ( File file : files ) {
          if ( file.isDirectory() ) {
            String folderNamePotentialPluginId = file.getName();
            pluginIds.add( folderNamePotentialPluginId );
          }
        }
      }
    }

    return pluginIds;
  }

  @Override
  protected boolean executeInstall( IPlugin plugin, IPluginVersion version ) {
    String parentFolderName = buildPluginsFolderPath( plugin );

    // Until plugin dependencies are implemented, check that the pentaho-big-data-plugin directory exists
    // before installing anything of type HadoopShim
    if ( plugin.getType().equals( MarketEntryType.HadoopShim ) ) {

      File bdPluginFolder = new File( parentFolderName ).getParentFile();
      if ( bdPluginFolder == null || !bdPluginFolder.exists() ) {
        return false;
      }
    }

    File pluginFolder = new File( parentFolderName + File.separator + plugin.getId() );
    this.getLogger().info( "Installing plugin in folder: " + pluginFolder.getAbsolutePath() );

    try {

      if ( pluginFolder.exists() ) {
        deleteDirectory( pluginFolder );
      }
      unzipMarketEntry( parentFolderName, version.getDownloadUrl() );
      if ( this.getInstalledPluginVersion( plugin ) == null ) {
        createVersionXML( plugin, version );
      }
    } catch ( KettleException e ) {
      this.getLogger().error( "ERROR on delete or create", e );
      return false;
    }

    return true;
  }

  @Override
  protected boolean executeUninstall( IPlugin plugin ) {
    String parentFolderName = buildPluginsFolderPath( plugin );
    File pluginFolder = new File( parentFolderName + File.separator + plugin.getId() );
    this.getLogger().info( "Uninstalling plugin in folder: " + pluginFolder.getAbsolutePath() );

    if ( !pluginFolder.exists() ) {
      this.getLogger().error( "No plugin was found in the expected folder : " + pluginFolder.getAbsolutePath() );
      return false;
    }

    try {
      URL pluginFolderURL = pluginFolder.toURI().toURL();
      PluginRegistry pluginRegistry = this.getPluginRegistry();
      Iterable<PluginInterface> pdiPlugins = pluginRegistry.findPluginsByFolder( pluginFolderURL );
      for ( PluginInterface pdiPlugin : pdiPlugins ) {

        // Do this on unloadPlugin method
        /*
        // unload plugin
        ClassLoader cl = PluginRegistry.getInstance().getClassLoader( plugin );
        if ( cl instanceof KettleURLClassLoader ) {
          ( (KettleURLClassLoader) cl ).closeClassLoader();
        }
        */

        // remove plugin from registry
        pluginRegistry.removePlugin( pdiPlugin.getPluginType(), pdiPlugin );
      }
    } catch ( MalformedURLException e1 ) {
      this.getLogger().error( e1.getLocalizedMessage(), e1 );
    }

    // delete plugin folder
    try {
      deleteDirectory( pluginFolder );
    } catch ( KettleException exception ) {
      this.getLogger().error( "Error deleting plugin folder on uninstall of plugin " + plugin.getId() );
      return false;
    }

    return true;
  }

  @Override
  public Collection<IPlugin> getPlugins() {
    Collection<IPlugin> plugins = super.getPlugins();

    // remove non PDI plugins
    CollectionUtils.filter( plugins, new Predicate() {
      @Override public boolean evaluate( Object plugin ) {
        if ( !( plugin instanceof IPlugin ) ) {
          return false;
        }
        IPlugin castedPlugin = (IPlugin) plugin;
        return castedPlugin.getType() != MarketEntryType.Platform;
      }
    } );

    return plugins;
  }



  /**
   * Builds and returns the path to the plugins folder.
   *
   * @param marketEntry
   * @return String the path to the plugins folder.
   */
  public String buildPluginsFolderPath( final IPlugin marketEntry ) {
    PluginInterface plugin = getPluginObject( marketEntry.getId() );
    if ( plugin != null && plugin.getPluginDirectory() != null ) {
      return new File( plugin.getPluginDirectory().getFile() ).getParent();
    } else {
      String subfolder = getInstallationSubfolder( marketEntry.getType() );

      // Use current directory (should be the Kettle distribution directory) as the root folder to install plugins
      // This is because plugin types are not guaranteed to search the ~/.kettle folder for plugins.
      return BASE_PLUGINS_FOLDER_NAME + ( subfolder == null ? "" : Const.FILE_SEPARATOR + subfolder );
    }
  }

  /**
   * Find the plugin object related to a pluginId.
   *
   * @param pluginId
   *          id of plugin
   * @return plugin object
   */
  private PluginInterface getPluginObject( String pluginId ) {
    PluginRegistry pluginRegistry = this.getPluginRegistry();
    for ( Class<? extends PluginTypeInterface> pluginType : pluginRegistry.getPluginTypes() ) {
      if ( pluginRegistry.findPluginWithId( pluginType, pluginId ) != null ) {
        return pluginRegistry.findPluginWithId( pluginType, pluginId );
      }
    }
    return null;
  }

  /**
   * Returns the folder name for the MarketEntries type.
   *
   * @param marketEntryType
   * @return
   */
  public static String getInstallationSubfolder( MarketEntryType marketEntryType ) {
    String subfolder;
    switch ( marketEntryType ) {
      case Step:
        subfolder = "steps";
        break;
      case JobEntry:
        subfolder = "jobentries";
        break;
      case Partitioner:
        subfolder = "steps";
        break;
      case SpoonPlugin:
        subfolder = "spoon";
        break;
      case Database:
        subfolder = "databases";
        break;
      /*
      case Repository:
        subfolder = "repositories";
        break;
        */
      case HadoopShim:
        subfolder = "pentaho-big-data-plugin" + File.separator + "hadoop-configurations";
        break;
      /*
      case General:
      */
      case Mixed:
        subfolder = "";
        break;

      default:
        subfolder = null;
    }
    return subfolder;
  }

  /**
   * This is a copy of method. That method works fine if the plugin is used in the same version it is built from. When
   * the plugin was dropped into PDI 4.2.1 then an invocation target exception was thrown when invoking
   * JarfileGenerator.deleteDirectory().
   *
   * I placed the method here even though the cause of the exception is not that obvious. The
   * JarfileGenerator.deleteDirectory method has not changed since 4.2.1.
   *
   * @param dir
   */
  private static void deleteDirectory( File dir ) throws KettleException {
    if ( dir != null ) {
      File[] files = dir.listFiles();
      if ( files != null ) {
        for ( int i = 0; i < files.length; i++ ) {
          if ( files[i].isDirectory() ) {
            deleteDirectory( files[i] );
          } else if ( !files[i].delete() ) {
            throw new KettleException( "Failed to delete " + files[i] );
          }
        }
      }
      if ( !dir.delete() ) {
        throw new KettleException( "Failed to delete directory " + dir );
      }
    }
  }

  private void createVersionXML( IPlugin marketEntry, IPluginVersion version ) throws KettleException {
    String pluginFolder = buildPluginsFolderPath( marketEntry ) + File.separator + marketEntry.getId();
    String versionPath = pluginFolder + File.separator + "version.xml";
    File parentFolder = new File( pluginFolder );
    File file = new File( versionPath );
    if ( file != null ) {
      BufferedWriter bufferedWriter = null;
      try {
        if ( !parentFolder.exists() ) {
          parentFolder.mkdirs();
        }

        FileWriter fw = new FileWriter( file.getAbsoluteFile() );
        bufferedWriter = new BufferedWriter( fw );
        bufferedWriter.write(
          "<version " + buildAttribute( "branch", version.getBranch() ) + " "
                      + buildAttribute( "buildId", version.getBuildId() ) + ">" +
            version.getVersion() +
          "</version>" );
      } catch ( IOException ioe ) {
        throw new KettleException( ioe );
      } finally {
        if ( bufferedWriter != null ) {
          try {
            bufferedWriter.close();
          } catch ( IOException ioe ) {
            throw new KettleException( ioe );
          }
        }
      }
    }
  }

  private static String buildAttribute( String name, String value ) {
    return nullOrEmpty( value ) ?
      "" :
      name + "='" + value + "'" ;
  }

  private static boolean nullOrEmpty( String string ) {
    return string == null || string.isEmpty();
  }

  /**
   * Unzips the plugin to the file system The passed MarkeyEntry has the URL of the zip file.
   *
   * @param folderName
   * @param marketEntry
   * @throws KettleException
   */
  private static void unzipMarketEntry( String folderName, String packageUrl ) throws KettleException {

    // Copy the file locally first
    //
    File tmpFile = null;
    InputStream inputStream = null;
    ZipInputStream zis = null;

    try {
      tmpFile = File.createTempFile( "plugin", ".zip" );
      org.apache.commons.io.FileUtils.copyURLToFile( new URL( packageUrl ), tmpFile );

      // Read the package, extract in folder
      //
      inputStream = new FileInputStream( tmpFile );
      zis = new ZipInputStream( inputStream );
      ZipEntry zipEntry = null;
      try {
        zipEntry = zis.getNextEntry();
      } catch ( IOException ioe ) {
        throw new KettleException( ioe );
      }
      byte[] buffer = new byte[1024];
      int bytesRead = 0;
      FileOutputStream fos = null;

      while ( zipEntry != null ) {
        try {
          File file = new File( folderName + File.separator + zipEntry.getName() );

          if ( zipEntry.isDirectory() ) {
            file.mkdirs();
          } else {
            file.getParentFile().mkdirs();

            fos = new FileOutputStream( file );
            while ( ( bytesRead = zis.read( buffer ) ) != -1 ) {
              fos.write( buffer, 0, bytesRead );
            }
          }

          zipEntry = zis.getNextEntry();
        } catch ( FileNotFoundException fnfe ) {
          throw new KettleException( fnfe );
        } catch ( IOException ioe ) {
          throw new KettleException( ioe );
        } finally {
          if ( fos != null ) {
            try {
              fos.close();
            } catch ( IOException e ) {
              // Ignore.
            }
          }
        }
      }
    } catch ( IOException e ) {
      throw new KettleException( "Unable to unzip file " + packageUrl, e );
    } finally {
      if ( zis != null ) {
        tmpFile.delete();
        try {
          zis.close();
        } catch ( Exception e ) {
          throw new KettleException( "Unable to close zip file stream (corrupt file?) of file " + tmpFile, e );
        }
      }
    }

  }

}
