package server;

import store.Store;
import store.StoreExpirationListener;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

public class Server extends Thread implements StoreExpirationListener {

    private static Server instance;
    public static Server instance(){
        return instance;
    }

    private final ServerSocket serverSocket;

    private final int port;

    public int getID_length() {
        return ID_length;
    }

    private final int ID_length;

    private final ServerConfiguration currentConfig;
    private final HashMap<String, Store> stores = new HashMap<>();
    private final ArrayList<ClientProcessor> clients = new ArrayList<>();

    //server states
    private boolean running = true;

    public Server(ServerConfiguration config) throws IOException {

        if(instance != null){
            throw new RuntimeException("Cannot create more than one server instance");
        }
        instance = this;

        if(config == null)
            throw new RuntimeException("server.ServerConfiguration cannot be null");

        //TODO: maybe it's up to the server.Server constructor to check config validity ?
        this.currentConfig = config;

        this.ID_length = currentConfig.IDLength();

        //apply config
        this.port = currentConfig.serverPort();

        //start server
        this.serverSocket = new ServerSocket(port);

    }

    @Override
    public synchronized void start() {
        this.running = true;
        super.start();
    }

    @Override
    public void run() {
        while(this.running) {
            try {
                final Socket s = serverSocket.accept();

                ClientProcessor processor = new ClientProcessor(s);

                processor.addThreadFinishListener(new Runnable() {
                    @Override
                    public void run() {
                        clients.remove(processor);
                        System.out.println("Removed client");
                    }
                });

                clients.add(processor);

                processor.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Generates a new unused ID for a new store. The ID will be generated according to the server's {@linkplain #currentConfig}
     * @return A generated string ID
     */
    public String generateID(){
        int len = currentConfig.IDLength();
        Random r = new Random();
        String s = null;
        String charSet = currentConfig.charSet();
        int charSetLen = charSet.length()-1;
        do{
            s = "";
            for(int i=0; i<len; i++){
                s += charSet.charAt(r.nextInt(charSetLen));
            }
        }while(storeExists(s)); //keep generating an ID if it's already used
        return s;
    }

    /**
     * Automatically generate an unused ID and create the directory.
     * @return The path to the new directory
     */
    public Store allocateStore(){
        try {
            String newID = generateID();
            Path p = currentConfig.storeDirectory().resolve(newID);

            Store s = new Store(newID, Files.createDirectories(p), currentConfig.defaultLeaseTime());
            registerStore(s);
            return s;

        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Error creating the store", e);
        }
    }

    /**
     * Looks up if a file with the specified ID exits, and returns a path to the file if it does.
     * @param id The requested ID
     * @return a path to the file if it exists, null if it doesn't exist
     */
    public Path requestFile(String id){

        if(id == null)
            throw new IllegalArgumentException("Argument 'ID' cannot be null");

        Store store = requestStore(id);

        if(store == null)
            return null;

        Path file;
        try {
            file = Files.list(store.path).findAny().get();
            return file;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Returns the store associated with the given ID.
     * @param id The ID
     * @return The found store, null if no store exist with this ID
     */
    public Store requestStore(String id){
        return stores.get(id);
    }

    /**
     * Invalidates store
     * @param s
     */
    public void invalidateStore(Store s){

        System.out.println("store.Store " + s.ID + " has been invalidated");
        try {
            deleteStore(s);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Returns a server.ServerConfiguration representing the server's current config state.
     * @return server.ServerConfiguration
     */
    public ServerConfiguration getCurrentConfig(){
        return this.currentConfig;
    }

    @Override
    public void onExpired(Store s) {
        System.out.println("store.Store " + s.ID + " expired");
        try {
            deleteStore(s);
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("Could not delete store upon expiration");
        }
    }

    private void restoreState(){
        String[] pathnames;

        File storePath_file = currentConfig.storeDirectory().toFile();

        pathnames = storePath_file.list();

        if(pathnames == null){ //storePath does not point to an existing folder, it means there's no state to restore
            System.out.println("No stores to restore");
            return;
        }

        for (String pathname : pathnames) {
            Path path = currentConfig.storeDirectory().resolve(pathname);
            if(Files.isDirectory(path)){
                System.out.println("restoring state of " + pathname);
                Store restoredStore = new Store(pathname,path , currentConfig.defaultLeaseTime()); //TODO: restore actual expiration date
                restoredStore.start();
                registerStore(restoredStore);
            }
        }
    }

    //=-=-=-=-=-=-=-=-=-=-= PRIVATE METHODS =-=-=-=-=-=-=-=-=-=

    private boolean storeExists(String id) {
        return stores.containsKey(id);
    }

    private void registerStore(Store s){
        System.out.println("New store registered at ID " + s.ID);
        s.addExpirationListener(this);
        stores.put(s.ID, s);
    }

    private void deleteStore(Store s) throws IOException{

        if(!stores.containsValue(s)){
            throw new RuntimeException("We received an event from a store that is not in our map. This should not happen");
        }

        deleteDirectory(s.path.toFile());
            /*Files.delete(filePath);
            Files.delete(s.path);*/
        s.removeExpirationListener(this);
        stores.remove(s.ID);

    }
    boolean deleteDirectory(File directoryToBeDeleted) {
        System.out.println("Deleting" + directoryToBeDeleted);
        File[] allContents = directoryToBeDeleted.listFiles();

        if (allContents != null) {
            System.out.println("Contains : " + allContents.length);
            for (File file : allContents) {
                if(file.isFile()) {
                    try{
                        Files.delete(file.toPath());
                    }catch(IOException e){
                            e.printStackTrace();
                    }

                }
                else
                    deleteDirectory(file);
            }
        }
        return directoryToBeDeleted.delete();
    }
}
