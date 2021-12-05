import java.io.*;
import java.net.*;
import java.util.concurrent.TimeUnit;

// import java.util.concurrent.TimeUnit;

public class Reciever {
    // packer buffer of network layer // and buffer index
    private static final Packet[] network_buffer = new Packet[7];
    private static int network_index = 6;
    // list of threads
    // private static List<Thread> thr_list =new ArrayList<Thread>();
    private static Thread[] thr_arr = new Thread[100];

    private static final int MAX_SEQ = 7;
    private static final int MAX_PKT = 4;
    public static event_type event = event_type.NULL;

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        ServerSocket s1 = new ServerSocket(1234);

        String recieved_respond = null;
        boolean close_connection = false;

        int next_frame_to_send = 0; /* MAX_SEQ > 1; used for outbound stream */
        int ack_expected = 0; /* oldest frame as yet unacknowledged */
        int frame_expected = 0; /* next frame expected on inbound stream */
        Frame r = new Frame(); /* scratch variable */
        Packet[] buffer = new Packet[MAX_SEQ + 1]; /* buffers for the outbound stream */
        int nbuffered = 0; /* # output buffers currently in use */
        int i = 0; /* used to index into the buffer array */

        enable_network_layer(); /* allow network_layer_ready events */
        Socket s = s1.accept();
        DataOutputStream data_out_s = new DataOutputStream(s.getOutputStream());
        DataInputStream data_in_s = new DataInputStream(s.getInputStream());

        while (true) {

            // To start the connection via the socket
            if (i == 0)
                data_out_s.writeUTF("Recieve Another");

            close_connection = wait_for_event(data_in_s, data_out_s); /** four possibilities: see event_type above */
            // System.out.printf("Reciever Event: %s%n", event);

            if (close_connection) {
                System.out.printf("Reciever Close Connection%n");
                System.out.printf("************************Done************************%n");
                data_in_s.close();
                data_out_s.close();
                s.close();
                s1.close();
                break;
            }

            switch (event) {
                case frame_arrival: /* a data or control frame has arrived */
                    r = from_physical_layer(data_in_s); /* get incoming frame from physical layer */

                    if (r.seq == frame_expected) {
                        /* Frames are accepted only in order. */
                        to_network_layer(r.info); /* pass packet to network layer */
                        frame_expected = inc(frame_expected);
                        send_data(next_frame_to_send, frame_expected, buffer, data_out_s);
                        /* advance lower edge of receiver's window */
                    }

                    /* Ack n implies n - 1, n - 2, etc. Check for this. */
                    while (between(ack_expected, r.ack, next_frame_to_send)) {
                        /* Handle piggybacked ack. */
                        nbuffered = nbuffered - 1; /* one frame fewer buffered */
                        // stop_timer(ack_expected); /* frame arrived intact; stop timer */
                        ack_expected = inc(ack_expected); /* contract sender's window */
                    }

                    nbuffered = nbuffered + 1; /* expand the sender's window */

                    /** transmit the frame */
                    next_frame_to_send = inc(next_frame_to_send); /* advance sender's upper window edge */
                    break;

                case cksum_err: /* just ignore bad frames */
                    break;

                // case timeout: /* trouble; retransmit all outstanding frames */
                // next_frame_to_send = ack_expected; /* start retransmitting here */
                // for (i = 1; i <= nbuffered; i++) {
                // send_data(next_frame_to_send, frame_expected, buffer, data_out_s); /** resend
                // 1 frame*/
                // next_frame_to_send = inc(next_frame_to_send); /* prepare to send the next one
                // */
                // }
            }

            if (nbuffered < MAX_SEQ)
                enable_network_layer();
            else
                disable_network_layer();
        }

    }

    static boolean between(int a, int b, int c) {
        /* Return true if (a <=b < c circularly; false otherwise. */
        if (((a <= b) && (b < c)) || ((c < a) && (a <= b)) || ((b < c) && (c < a)))
            return (true);
        else
            return (false);
    }

    static void send_data(int frame_nr, int frame_expected, Packet[] buffer, DataOutputStream dos)
            throws IOException {
        /* Construct and send a data frame. */

        Frame s = new Frame(); /* scratch variable */

        s.kind = frame_kind.ack; // will set it to ack in reciever code
        s.seq = frame_nr; /* insert sequence number into frame */
        s.ack = (frame_expected + MAX_SEQ) % (MAX_SEQ + 1); /* piggyback ack */
        dos.writeUTF("Acknowledge Sent"); // to indicate that the frame is sent
        to_physical_layer(s, dos); /* transmit the frame */
        System.out.printf("Frame with Ack %d is sent%n", s.ack);

        // start_timer(frame_nr); /* start the timer running */
    }

    /* Wait for an event to happen; return its type in event. */
    static boolean wait_for_event(DataInputStream dis, DataOutputStream dos) throws IOException {
        String recieved_respond = null;

        while (true) {
            // break on frame arrival
            recieved_respond = dis.readUTF();
            if (recieved_respond.equals("Frame Sent")) {
                event = event_type.frame_arrival;
                break;
            } else if (recieved_respond.equals("Frame not sent")) {
                event = event_type.cksum_err;
                break;

            } else if (recieved_respond.equals("Recieve Another")) {
                break;
            } else if (recieved_respond.equals("Timeout")) {
                dos.writeUTF("Recieve Another");
            } else if (recieved_respond.equals("Close connection. Bye!")) {
                return true;
            }
        }
        return false;
    } ////////////// pass event & datainput stream

    /* Go get an inbound frame from the physical layer and copy it to r. */
    static Frame from_physical_layer(DataInputStream dis) throws IOException {
        Frame f = new Frame();
        String data;
        while (true) {
            // break on frame arrival
            data = dis.readUTF();
            if (data.equals("Sending Frame")) {
                f.seq = dis.readInt();
                f.info.data = dis.readChar();

                System.out.printf("Seq %d", f.seq);
                System.out.printf(" ,, Data %c%n", f.info.data);
                break;
            }
        }

        return f;
    } // socket

    /* Pass the frame to the physical layer for transmission. */
    static void to_physical_layer(Frame f, DataOutputStream dos) throws IOException {
        dos.writeUTF("Sending Ack");
        dos.writeInt(f.ack);
    }// socket

    /* Deliver information from an inbound frame to the network layer. */
    static void to_network_layer(Packet p) {

    } // dummy

    /* Allow the network layer to cause a network_layer_ready event. */
    static void enable_network_layer() {
        System.out.printf("Reciever Enable Network%n");
        event = event_type.network_layer_ready;
    } // change the event

    /* Forbid the network layer from causing a network_layer_ready event. */
    static void disable_network_layer() {
        if (event == event_type.network_layer_ready)
            event = event_type.NULL;
    }

    static int inc(int k) {
        k = (k < MAX_SEQ) ? k + 1 : 0;
        return k;
    }
}

enum event_type {
    frame_arrival, cksum_err, timeout, network_layer_ready, NULL;

    public static void main(String[] args) {
    }
}

class Packet implements Serializable {
    public static char data;

    public Packet(char d) {
        data = d;
    }
}

enum frame_kind {
    data, ack, nak
}

class Frame implements Serializable {
    frame_kind kind;
    int seq = 0;
    int ack = 0;
    Packet info;
}