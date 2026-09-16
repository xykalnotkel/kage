package dev.kage.common;

/**
 * Wire protocol shared by the server (dex, shell/root), the manager app and third-party clients.
 *
 * Two transports are supported and they intentionally share this file so that both sides can
 * never drift apart:
 *
 *  1. Binder  - the classic Shizuku style path. The server pushes an IBinder into a client's
 *               ContentProvider which then hands it over to the app process.
 *  2. File    - a fallback channel that only needs a shared directory (the manager's external
 *               files dir). Used when binder publishing is not possible on a device.
 */
public final class Protocol {

    private Protocol() {}

    /** Protocol version. Bump on breaking wire changes. */
    public static final int VERSION = 2;

    /** Package of the manager app. */
    public static final String MANAGER_PACKAGE = "dev.kage.manager";

    /** Action a client app uses to open the manager's permission dialog. */
    public static final String REQUEST_PERMISSION_ACTION = MANAGER_PACKAGE + ".intent.action.REQUEST_PERMISSION";

    /** Permission a client app must hold before the server talks to it. */
    public static final String PERMISSION = "dev.kage.permission.API";

    /** Authority of a client provider is "<package>" + this suffix. */
    public static final String AUTHORITY_SUFFIX = ".kage";

    /** Name of the provider class shipped in the client library. */
    public static final String PROVIDER_CLASS = "dev.kage.provider.KageProvider";

    // ---------------------------------------------------------------- binder

    public static final String BINDER_DESCRIPTOR = "dev.kage.server.IKageService";
    public static final String PROCESS_DESCRIPTOR = "dev.kage.server.IRemoteProcess";

    /** Bundle keys used while pushing the binder into a client. */
    public static final String EXTRA_BINDER = "dev.kage.extra.BINDER";
    public static final String EXTRA_TOKEN = "dev.kage.extra.TOKEN";

    /** ContentProvider methods. */
    public static final String METHOD_SEND_BINDER = "sendBinder";   // server -> client
    public static final String METHOD_GET_BINDER = "getBinder";     // app process -> own provider

    /** Transaction codes of IKageService. */
    public static final int TX_GET_VERSION = 1;
    public static final int TX_GET_STATUS = 2;
    public static final int TX_EXIT = 3;
    public static final int TX_CHECK_SELF_PERMISSION = 4;
    public static final int TX_REQUEST_PERMISSION = 5;
    public static final int TX_REVOKE_PERMISSION = 6;
    public static final int TX_GET_GRANTED_PACKAGES = 7;
    public static final int TX_SYNC_GRANTS = 8;
    public static final int TX_LIST_USERS = 9;
    public static final int TX_NEW_PROCESS = 10;
    public static final int TX_TRANSACT = 11;
    public static final int TX_RESOLVE_TRANSACTION = 12;
    public static final int TX_GET_SYSTEM_PROPERTY = 13;
    public static final int TX_SET_SYSTEM_PROPERTY = 14;
    public static final int TX_GET_BINDER_PUSH_STATE = 15;
    public static final int TX_KILL_PACKAGE_PROCESSES = 16;
    public static final int TX_PUSH_TO_PACKAGE = 17;

    /** Transaction codes of IRemoteProcess. */
    public static final int PTX_GET_INPUT_STREAM = 1;
    public static final int PTX_GET_OUTPUT_STREAM = 2;
    public static final int PTX_GET_ERROR_STREAM = 3;
    public static final int PTX_WAIT_FOR = 4;
    public static final int PTX_EXIT_VALUE = 5;
    public static final int PTX_DESTROY = 6;
    public static final int PTX_IS_ALIVE = 7;

    // ------------------------------------------------------------------ file

    /** Directory names below the shared directory used by the file transport. */
    public static final String DIR_REQUESTS = "req";
    public static final String DIR_RESPONSES = "res";
    public static final String DIR_OUTPUT = "out";
    /** Heartbeat written by the server every second. */
    public static final String FILE_STATUS = "status.json";
    /** Dump of the grant list, written by the server. */
    public static final String FILE_GRANTS = "grants.json";
    /** stdout/stderr of the server process itself. */
    public static final String FILE_SERVER_LOG = "server.log";
    /** Name of the generated start script. */
    public static final String START_SCRIPT = "start.sh";

    /** File transport: request types. */
    public static final String CMD_PING = "ping";
    public static final String CMD_STATUS = "status";
    public static final String CMD_EXEC = "exec";
    public static final String CMD_EXEC_WAIT = "execWait";
    public static final String CMD_KILL = "kill";
    public static final String CMD_GRANTS = "grants";
    public static final String CMD_SYNC_GRANTS = "syncGrants";
    public static final String CMD_TRANSACT = "transact";
    public static final String CMD_RESOLVE = "resolve";
    public static final String CMD_SHUTDOWN = "shutdown";
    public static final String CMD_PUSH = "pushBinder";

    /** Debian-style runtime directory, writable by both shell and root. */
    public static final String RUNTIME_DIR = "/data/local/tmp/kage";
}
