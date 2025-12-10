Client - 2 threads
    - Messages should not contain /n
    - Adding "/" to none command messages that start with'/' Example: "/helloWorld" >> "//helloWorld" (This extra '/' must not be printed)
    - Process server messages for human friendly output

Server - multiplex
    - Buffer and segmented messages
    - chat Rooms
    - User unique nickname
    - Chat room unique name
    - inti/outside/inside user session management
    - transition table
    - private messages
    - Double Check Messages based on the Maquinhas example(due to automatic tests)
    - Test ncat/netcat/nc server buffering test
  
TODO:
    Client:
    - Handle messages with /n
    - Testing //NotACommand messages
    - Test Printing messages
    Server:
    - Test buffering messages
    - Private messages
    - Test printing messages