package dev.pavlov.foldlidshim;

interface IShellService {
    // Reserved Shizuku transaction code: stop the UserService process.
    void destroy() = 16777114;

    // Runs "cmd device_state state <arg>", arg is "0" or "reset".
    // Returns "exit=<code>\n<stdout and stderr>".
    String runDeviceState(String arg) = 1;
}
