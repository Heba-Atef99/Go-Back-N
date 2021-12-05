import java.io.*;
import java.net.*;
import java.util.concurrent.TimeUnit;

public class Sender {
    // packer buffer of network layer // and buffer index
    private static char[] network_buffer = new char[7];
    private static int network_index = 6;
    // list of threads
    // private static List<Thread> thr_list =new ArrayList<Thread>();
    private static Thread[] thr_arr = new Thread[100];

    private static final int MAX_SEQ = 7;
    private static final int MAX_PKT = 7;
    public static event_type event = event_type.NULL;
    // private static Packet p = new Packet();

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        set_Network_Buffer();

        Socket s = new Socket("127.0.0.1", 1234);
        String recieved_respond = null;
        boolean close_connection = false;

        DataOutputStream data_out_s = new DataOutputStream(s.getOutputStream());
        DataInputStream data_in_s = new DataInputStream(s.getInputStream());

        int next_frame_to_send = 0; /* MAX_SEQ > 1; used for outbound stream */
        int ack_expected = 0; /* oldest frame as yet unacknowledged */
        int frame_expected = 0; /* next frame expected on inbound stream */
        Frame r = null; /* scratch variable */
        Packet[] buffer = new Packet[MAX_SEQ + 1]; /* buffers for the outbound stream */
        int nbuffered = 0; /* # output buffers currently in use */
        int i = 0; /* used to index into the buffer array */

        enable_network_layer(); /* allow network_layer_ready events */

        while (true) {
            close_connection = wait_for_event(data_in_s); /* four possibilities: see event_type above */
            // System.out.printf("Sender Event: %s%n", event);

            switch (event) {
                case network_layer_ready: /* the network layer has a packet to send */
                    /* Accept, save, and transmit a new frame. */
                    buffer[next_frame_to_send] = from_network_layer(data_out_s); /* fetch new packet */
                    nbuffered = nbuffered + 1; /* expand the sender's window */
                    if (network_index + 1 == 5) {
                        next_frame_to_send = inc(next_frame_to_send); /*
                                                                       * advance sender's upper
                                                                       * window edge
                                                                       */
                        data_out_s.writeUTF("Frame not sent");

                    } else {
                        send_data(next_frame_to_send, frame_expected, buffer, data_out_s); /* transmit the frame */
                        next_frame_to_send = inc(next_frame_to_send);
                        /* advance sender's upper window edge */ }

                    // data_out_s.writeUTF("Recieve Another");
                    break;

                case frame_arrival: /* a data or control frame has arrived */
                    r = from_physical_layer(data_in_s); /* get incoming frame from physical layer */

                    if (r.seq == frame_expected) {
                        /* Frames are accepted only in order. */
                        to_network_layer(r.info); /* pass packet to network layer */
                        frame_expected = inc(frame_expected); /* advance lower edge of receiver's window */
                    }

                    /* Ack n implies n - 1, n - 2, etc. Check for this. */
                    while (between(ack_expected, r.ack, next_frame_to_send)) {
                        /* Handle piggybacked ack. */
                        nbuffered = nbuffered - 1; /* one frame fewer buffered */
                        stop_timer(ack_expected); /* frame arrived intact; stop timer */
                        System.out.println("Timer of frame " + ack_expected + " is stopped");
                        ack_expected = inc(ack_expected); /* contract sender's window */
                    }
                    // data_out_s.writeUTF("Recieve Another");
                    if (r.ack == MAX_SEQ - 1) {
                        close_connection = true;
                    }
                    break;

                case cksum_err: /* just ignore bad frames */
                    break;

                case timeout: /* trouble; retransmit all outstanding frames */
                    next_frame_to_send = ack_expected; /* start retransmitting here */
                    network_index = MAX_PKT - ack_expected;
                    for (i = 1; i <= nbuffered; i++) {
                        buffer[next_frame_to_send] = from_network_layer(data_out_s);
                        send_data(next_frame_to_send, frame_expected, buffer, data_out_s); /* resend 1 frame */
                        next_frame_to_send = inc(next_frame_to_send); /* prepare to send the next one */

                    }
                    System.out.println("Timeout event ");

            }
            if (close_connection == true & (nbuffered == 0))
                break;

            if (nbuffered < MAX_SEQ)
                enable_network_layer();
            else
                disable_network_layer();
        }

        data_out_s.writeUTF("Close connection. Bye!");
        System.out.printf("Sender Close Connection%n");
        System.out.printf("************************Done************************%n");
        data_in_s.close();
        data_out_s.close();
        s.close();
    }

    static boolean between(int a, int b, int c) {
        /* Return true if (a <=b < c circularly; false otherwise. */
        if (((a <= b) && (b < c)) || ((c < a) && (a <= b)) || ((b < c) && (c < a)))
            return (true);
        else
            return (false);
    }

    static void send_data(int frame_nr, int frame_expected, Packet[] buffer, DataOutputStream dos) throws IOException {
        /* Construct and send a data frame. */
        Frame s = new Frame(); /* scratch variable */

        s.info = buffer[frame_nr]; /* insert packet into frame */
        s.seq = frame_nr; /* insert sequence number into frame */
        s.ack = (frame_expected + MAX_SEQ) % (MAX_SEQ); /* piggyback ack */
        dos.writeUTF("Frame Sent"); // to indicate that the frame is sent
        to_physical_layer(s, dos); /* transmit the frame */
        start_timer(frame_nr, dos); /* start the timer running */
        System.out.printf("Frame with seq %d is sent .... data %c%n", frame_nr, s.info.data);
        System.out.println("Timer of frame " + frame_nr + " started");

        // dos.writeUTF("Recieve Another");
    }

    static void set_Network_Buffer() {
        char[] alpha = { 'A', 'B', 'C', 'D', 'E', 'F', 'G' };
        for (int j = 0; j < 7; j++) {
            network_buffer[j] = alpha[j];
        }

    }

    /* Wait for an event to happen; return its type in event. */
    static boolean wait_for_event(DataInputStream dis) throws IOException {
        String recieved_respond = null;
        while (true) {
            // break on frame arrival
            if (event == event_type.timeout)
                break;
            System.out.println(event);
            recieved_respond = dis.readUTF();

            if (recieved_respond.equals("Acknowledge Sent")) {
                event = event_type.frame_arrival;
                break;
            }

            else if (recieved_respond.equals("Recieve Another")) {
                break;
            }

            else if (recieved_respond.equals("Close connection. Bye!")) {
                return true;
            }
        }
        return false;
    } ////////////// pass event & datainput stream

    /* Go get an inbound frame from the physical layer and copy it to r. */
    static Frame from_physical_layer(DataInputStream dis) throws IOException, ClassNotFoundException {
        Frame f = new Frame();
        String data;
        while (true) {
            // break on frame arrival
            data = dis.readUTF();

            if (data.equals("Sending Ack")) {
                f.ack = dis.readInt();
                System.out.printf("Recieved Ack %d%n", f.ack);
                f.kind = frame_kind.ack;
                break;
            }
        }

        return f;
    } // socket

    /* Pass the frame to the physical layer for transmission. */
    static void to_physical_layer(Frame f, DataOutputStream dos) throws IOException {
        dos.writeUTF("Sending Frame");
        dos.writeInt(f.seq);
        dos.writeChar(f.info.data);
    }// socket

    /* Start the clock running and enable the timeout event. */
    static void start_timer(int k, DataOutputStream dos) {
        Timer timer = new Timer(event, dos);
        Thread thr = new Thread(timer);
        thr_arr[k] = thr;
        thr.start();

    } // thread

    /* Stop the clock and disable the timeout event. */
    static void stop_timer(int k) {
        thr_arr[k].interrupt();

    } // stop thread

    /* Fetch a packet from the network layer for transmission on the channel. */
    static Packet from_network_layer(DataOutputStream dos) throws IOException {
        network_index--;
        // if (network_index + 1 == 5) {
        // return null;
        if (/* (network_index + 1 != 5) & */ (network_index + 1 >= 0)) {
            Packet p = new Packet(network_buffer[network_index + 1]);
            return p;
        } else {
            return null;
        }

    } // array

    /* Deliver information from an inbound frame to the network layer. */
    static void to_network_layer(Packet p) {

    } // dummy

    /* Allow the network layer to cause a network_layer_ready event. */
    static void enable_network_layer() {
        event = event_type.network_layer_ready;
        // System.out.printf("Sender Enable Network%n");

    } // change the event

    /* Forbid the network layer from causing a network_layer_ready event. */
    static void disable_network_layer() {
        if (event == event_type.network_layer_ready) {
            event = event_type.NULL;
            System.out.printf("Sender Disable Network%n");
        }
    }

    static int inc(int k) {
        k = (k < MAX_SEQ) ? k + 1 : 0;
        return k;
    }
}

class Timer implements Runnable {
    private int max = 3;
    event_type ee;
    DataOutputStream dos;

    public Timer(event_type e, DataOutputStream dos) {
        ee = e;
        this.dos = dos;
    }

    @Override
    public void run() {

        int i = 0;
        try {
            while (i <= max) {

                if (Thread.currentThread().isInterrupted())
                    break;
                TimeUnit.SECONDS.sleep(1);
                i++;

            }
            if (i > max) {
                ee = event_type.timeout;
                System.out.println("Timeout");
                dos.writeUTF("Timeout");
            } else
                System.out.println("Thread ended normally");

        } catch (InterruptedException e) {
            System.out.println(e.getMessage());
        } catch (IOException g) {
            System.out.println(g.getMessage());
        }
    }
}

enum event_type {
    frame_arrival, cksum_err, timeout, network_layer_ready, NULL;

    public static void main(String[] args) {
    }
}

class Packet {
    public static char data;

    public Packet(char d) {
        data = d;
    }
}

enum frame_kind {
    data, ack, nak
}

class Frame {
    frame_kind kind;
    int seq = 0;
    int ack = 0;
    Packet info;
}
