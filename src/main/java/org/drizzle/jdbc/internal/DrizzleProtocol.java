package org.drizzle.jdbc.internal;

import org.drizzle.jdbc.internal.packet.*;
import org.drizzle.jdbc.internal.packet.buffer.ReadUtil;
import org.drizzle.jdbc.internal.query.Query;
import org.drizzle.jdbc.internal.query.drizzle.DrizzleQuery;
import org.drizzle.jdbc.internal.queryresults.DrizzleQueryResult;
import org.drizzle.jdbc.internal.queryresults.DrizzleUpdateResult;
import org.drizzle.jdbc.internal.queryresults.QueryResult;
import org.drizzle.jdbc.internal.queryresults.ColumnInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import java.net.Socket;
import java.io.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Arrays;

/**
 * TODO: refactor, clean up
 * TODO: when should i read up the resultset?
 * TODO: thread safety?
 * TODO: exception handling
 * User: marcuse
 * Date: Jan 14, 2009
 * Time: 4:06:26 PM
 */
public class DrizzleProtocol implements Protocol {
    private final static Logger log = LoggerFactory.getLogger(DrizzleProtocol.class);
    private boolean connected=false;
    private final Socket socket;
    private final BufferedInputStream reader;
    private final BufferedOutputStream writer;
    private final String version;
    private boolean readOnly=false;
    private boolean autoCommit;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private List<Query> batchList;

    /**
     * Get a protocol instance
     *
     * @param host the host to connect to
     * @param port the port to connect to
     * @param database the initial database
     * @param username the username
     * @param password the password
     * @throws QueryException if there is a problem reading / sending the packets
     */
    public DrizzleProtocol(String host, int port, String database, String username, String password) throws QueryException {
        this.host=host;
        this.port=port;
        this.database=(database==null?"":database);
        this.username=(username==null?"":username);
        this.password=(password==null?"":password);
        SocketFactory socketFactory = SocketFactory.getDefault();
        try {
            socket = socketFactory.createSocket(host,port);
        } catch (IOException e) {
            throw new QueryException("Could not connect socket",e);
        }
        log.info("Connected to: {}:{}",host,port);
        batchList=new ArrayList<Query>();
        try {
            reader = new BufferedInputStream(socket.getInputStream(),16384);
            writer = new BufferedOutputStream(socket.getOutputStream(),16384);
            GreetingReadPacket greetingPacket = new GreetingReadPacket(reader);
            log.debug("Got greeting packet: {}",greetingPacket);
            this.version=greetingPacket.getServerVersion();
            Set<ServerCapabilities> serverCapabilities = greetingPacket.getServerCapabilities();
            serverCapabilities.removeAll(Arrays.asList(ServerCapabilities.INTERACTIVE, ServerCapabilities.SSL, ServerCapabilities.ODBC, ServerCapabilities.NO_SCHEMA));
            serverCapabilities.addAll(Arrays.asList(ServerCapabilities.CONNECT_WITH_DB,ServerCapabilities.TRANSACTIONS));
            ClientAuthPacket cap = new ClientAuthPacket(this.username,this.password,this.database,serverCapabilities);
            byte [] bytes = cap.toBytes((byte)1);
            writer.write(bytes);
            writer.flush();
            log.debug("Sending auth packet: {}",cap);
            ResultPacket resultPacket = ResultPacketFactory.createResultPacket(reader);
            log.debug("Got result: {}",resultPacket);
            selectDB(this.database);
            setAutoCommit(true);
        } catch (IOException e) {
            throw new QueryException("Could not connect",e);
        }
    }

    /**
     * Closes socket and stream readers/writers
     * @throws QueryException if the socket or readers/writes cannot be closed
     */
    public void close() throws QueryException {
        log.debug("Closing...");
        try {
            ClosePacket closePacket = new ClosePacket();
            writer.write(closePacket.toBytes((byte)0));
            writer.close();
            reader.close();
            socket.close();
        } catch(IOException e){
            throw new QueryException("Could not close connection",e);
        }
        this.connected=false;
    }

    /**
     *
     * @return true if the connection is closed
     */
    public boolean isClosed() {
        return !this.connected;
    }

    
    /**
     * create a DrizzleQueryResult - precondition is that a result set packet has been read
     * @param packet the result set packet from the server
     * @return a DrizzleQueryResult
     * @throws IOException when something goes wrong while reading/writing from the server
     */
    private DrizzleQueryResult createDrizzleQueryResult(ResultSetPacket packet) throws IOException {
        List<ColumnInformation> columnInformation = new ArrayList<ColumnInformation>();
        for(int i=0;i<packet.getFieldCount();i++) {
            ColumnInformation columnInfo = FieldPacket.columnInformationFactory(reader);
            columnInformation.add(columnInfo);
        }
        EOFPacket eof = new EOFPacket(reader);
        List<List<ValueObject>> valueObjects = new ArrayList<List<ValueObject>>();
        while(true) {
            if(ReadUtil.eofIsNext(reader)) {
                new EOFPacket(reader);
                return new DrizzleQueryResult(columnInformation,valueObjects);
            }
            RowPacket rowPacket = new RowPacket(reader,columnInformation);
            valueObjects.add(rowPacket.getRow());
        }
    }
    
    public void selectDB(String database) throws QueryException {
        log.debug("Selecting db {}",database);
        SelectDBPacket packet = new SelectDBPacket(database);
        byte packetSeqNum=0;
        byte [] b = packet.toBytes(packetSeqNum);
        try {
            writer.write(b);
            writer.flush();
            ResultPacketFactory.createResultPacket(reader);
        } catch (IOException e) {
            throw new QueryException("Could not select database",e);
        }
    }

    public String getVersion() {
        return version;
    }

    public void setReadonly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean getReadonly() {
        return readOnly;
    }

    public void commit() throws QueryException {
        log.debug("commiting transaction");
        executeQuery(new DrizzleQuery("COMMIT"));
    }

    public void rollback() throws QueryException {
        log.debug("rolling transaction back");
        executeQuery(new DrizzleQuery("ROLLBACK"));
    }

    public void rollback(String savepoint) throws QueryException {
        log.debug("rolling back to savepoint {}",savepoint);
        executeQuery(new DrizzleQuery("ROLLBACK TO SAVEPOINT "+savepoint));
    }

    public void setSavepoint(String savepoint) throws QueryException {
        log.debug("setting a savepoint named {}",savepoint);
        executeQuery(new DrizzleQuery("SAVEPOINT "+savepoint));
    }
    public void releaseSavepoint(String savepoint) throws QueryException {
        log.debug("releasing savepoint named {}",savepoint);
        executeQuery(new DrizzleQuery("RELEASE SAVEPOINT "+savepoint));
    }

    public void setAutoCommit(boolean autoCommit) throws QueryException {
        this.autoCommit = autoCommit;
        executeQuery(new DrizzleQuery("SET autocommit="+(autoCommit?"1":"0")));
    }

    public boolean getAutoCommit() {
        return autoCommit;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getDatabase() {
        return database;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public boolean ping() throws QueryException {
        PingPacket pingPacket = new PingPacket();
        try {
            writer.write(pingPacket.toBytes((byte)0));
            writer.flush();
            log.debug("Sent ping packet");
            return ResultPacketFactory.createResultPacket(reader).getResultType()==ResultPacket.ResultType.OK;
        } catch (IOException e) {
            throw new QueryException("Could not ping",e);
        }
    }

    public QueryResult executeQuery(Query dQuery) throws QueryException {
        log.debug("Executing streamed query: {}",dQuery);
        StreamedQueryPacket packet = new StreamedQueryPacket(dQuery);
        int i=0;
        try {
            packet.sendQuery(writer);
        } catch (IOException e) {
            throw new QueryException("Could not send query",e);
        }

        ResultPacket resultPacket = null;
        try {
            resultPacket = ResultPacketFactory.createResultPacket(reader);
        } catch (IOException e) {
            throw new QueryException("Could not read response",e);
        }

        switch(resultPacket.getResultType()) {
            case ERROR:
                log.warn("Could not execute query {}: {}",dQuery, ((ErrorPacket)resultPacket).getMessage());
                throw new QueryException("Could not execute query: "+((ErrorPacket)resultPacket).getMessage());
            case OK:
                OKPacket okpacket = (OKPacket)resultPacket;
                QueryResult updateResult = new DrizzleUpdateResult(okpacket.getAffectedRows(),
                                                                            okpacket.getWarnings(),
                                                                            okpacket.getMessage(),
                                                                            okpacket.getInsertId());
                log.debug("OK, {}", okpacket.getAffectedRows());
                return updateResult;
            case RESULTSET:
                log.debug("SELECT executed, fetching result set");
                try {
                    return this.createDrizzleQueryResult((ResultSetPacket)resultPacket);
                } catch (IOException e) {
                    throw new QueryException("Could not get query result",e);
                }
            default:
                log.error("Could not parse result...");
                throw new QueryException("Could not parse result");
        }

    }

    public void addToBatch(Query dQuery) {
        log.info("Adding query to batch");
        batchList.add(dQuery);
    }
    public List<QueryResult> executeBatch() throws QueryException {
        log.info("executing batch");
        List<QueryResult> retList = new ArrayList<QueryResult>(batchList.size());
        int i=0;
        for(Query query : batchList) {
            log.info("executing batch query");
            retList.add(executeQuery(query));
        }
        clearBatch();
        return retList;

    }

    public void clearBatch() {
        batchList.clear();
    }
}