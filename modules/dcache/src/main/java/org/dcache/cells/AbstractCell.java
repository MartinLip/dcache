package org.dcache.cells;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.math.BigInteger;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import diskCacheV111.util.CacheException;
import diskCacheV111.vehicles.Message;

import dmg.cells.nucleus.CDC;
import dmg.cells.nucleus.CellAdapter;
import dmg.cells.nucleus.CellAddressCore;
import dmg.cells.nucleus.CellEndpoint;
import dmg.cells.nucleus.CellMessage;
import dmg.cells.nucleus.CellMessageReceiver;
import dmg.cells.nucleus.NoRouteToCellException;
import dmg.cells.nucleus.Reply;
import dmg.cells.nucleus.UOID;
import dmg.util.Args;

/**
 * Abstract cell implementation providing features needed by many
 * dCache cells.
 *
 * <h2>Automatic dispatch of dCache messages to message handler</h2>
 *
 * See org.dcache.util.CellMessageDispatcher for details.
 *
 * <h2>Initialisation</h2>
 *
 * AbstractCell provides the <code>init</code> method for performing
 * cell initialisation. This method is executed in a thread allocated
 * from the cells thread, and thus the thread group and log4j context
 * are automatically inherited for any threads created during
 * initialisation. Any log messages generated from within the
 * <code>init</code> method are correctly attributed to the
 * cell. Subclasses should override <code>init</code> rather than
 * performing initialisation steps in the constructor.
 *
 * The <code>init</code> method is called by <code>doInit</code>,
 * which makes sure <code>init</code> is executed in the correct
 * thread. <code>doInit</code> also enables cells message delivery by
 * calling <code>CellAdapter.start</code>. Should <code>init</code>
 * throw an exception, then <code>doInit</code> immediately kills the
 * cell and logs an error message.
 *
 * Subclasses must call doInit (preferably from their constructor) for
 * any of this to work.
 *
 * <h2>Option parsing</h2>
 *
 * AbstractCell supports automatic option parsing based on annotations
 * of fields. A field is annotated with the Option annotation. The
 * annotation supports the following attributes:
 *
 * <dl>
 * <dt>name</dt>
 * <dd>The name of the option.</dd>
 *
 * <dt>description</dt>
 * <dd>A one line description of the option.</dd>
 *
 * <dt>defaultValue</dt>
 * <dd>The default value if the option is not specified,
 * specified as a string.</dd>
 *
 * <dt>unit</dt>
 * <dd>The unit of the value, if any, e.g. seconds.</dd>
 *
 * <dt>required</dt>
 * <dd>Whether this is a mandatory option. Defaults to false.</dd>
 *
 * <dt>log</dt>
 * <dd>Whether to log the value of the option during startup.
 * Defaults to true, but should be disabled for sensitive
 * information.</dd>
 * </dl>
 *
 * Options are automatically converted to the type of the field. In
 * case of non-POD fields, the class must have a one-argument
 * constructor taking a String. The File class is an example of such a
 * class.
 *
 * By defaults options are logged at the info level. The description
 * and unit should be formulated in such a way that the a message can
 * be formed as "<description> set to <value> <unit>".
 *
 * In case a required option is missing, an IllegalArgumentException
 * is thrown during option parsing.
 *
 * It is important that fields used for storing options do not have an
 * initializer. An initializer would overwrite the value retrieved
 * from the option. Empty Strings will become null.
 *
 * Example code:
 *
 * <code>
 *   @Option(
 *       name = "maxPinDuration",
 *       description = "Max. lifetime of a pin",
 *       defaultValue = "86400000", // one day
 *       unit = "ms"
 *   )
 *   protected long _maxPinDuration;
 *
 * @see org.dcache.cells.CellMessageDispatcher
 */
public class AbstractCell extends CellAdapter implements CellMessageReceiver
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractCell.class);

    private static final String MSG_UOID_MISMATCH =
        "A reply [%s] was generated by a message listener, but the " +
        "message UOID indicates that another message listener has " +
        "already replied to the message.";
    private static final String MSG_REPLY_TO_REPLY =
        "A reply [%s] was generated by a message listener, but the " +
        "message was already a reply to which no reply can be sent.";
    private static final String MSG_ALREADY_FORWARDED =
        "A reply [%s] was generated by a message listener, but the " +
        "message was already forwarded and thus no reply can be sent.";
    private static final String MSG_NO_NEXT_DESTINATION =
        "A message [%s] was received for forwarding, but the message contains " +
        "no address to forward it to.";

    @Option(
        name = "monitor",
        description = "Cell message monitoring",
        defaultValue = "false"
    )
    protected boolean _isMonitoringEnabled;

    @Option(
        name = "cellClass",
        description = "Cell classification"
    )
    protected String _cellClass;

    /**
     * Timer for periodic low-priority maintenance tasks. Shared among
     * all AbstractCell instances. Since a Timer is single-threaded,
     * it is important that the timer is not used for long-running or
     * blocking tasks, nor for time critical tasks.
     */
    protected final static Timer _timer = new Timer("Cell timer", true);

    /**
     * Task for calling the Cell nucleus message timeout mechanism.
     */
    private TimerTask _timeoutTask;

    /**
     * Helper object used to dispatch messages to message listeners.
     */
    protected final CellMessageDispatcher _messageDispatcher =
        new CellMessageDispatcher("messageArrived");

    /**
     * Helper object used to dispatch messages to forward to message
     * listeners.
     */
    protected final CellMessageDispatcher _forwardDispatcher =
        new CellMessageDispatcher("messageToForward");

    /**
     * Name of context variable to execute during setup, or null.
     */
    protected String _definedSetup;

    protected MessageProcessingMonitor _monitor;

    /**
     * Strips the first argument if it starts with an exclamation
     * mark.
     */
    private static Args stripDefinedSetup(Args args)
    {
        args = new Args(args);
        if ((args.argc() > 0) && args.argv(0).startsWith("!")) {
            args.shift();
        }
        return args;
    }

    /**
     * Returns the defined setup declaration, or null if there is no
     * defined setup.
     *
     * The defined setup is declared as the first argument and starts
     * with an exclamation mark.
     */
    private static String getDefinedSetup(Args args)
    {
        if ((args.argc() > 0) && args.argv(0).startsWith("!")) {
            return args.argv(0).substring(1);
        } else {
            return null;
        }
    }

    /**
     * Returns the cell type specified as option 'cellType', or
     * "Generic" if the option was not given.
     */
    static private String getCellType(Args args)
    {
        String type = args.getOpt("cellType");
        return (type == null) ? "Generic" : type;
    }

    public AbstractCell(String cellName, String arguments)
    {
        this(cellName, new Args(arguments));
    }

    public AbstractCell(String cellName, Args arguments)
    {
        this(cellName, getCellType(arguments), arguments);
    }

    /**
     * Constructs an AbstractCell.
     *
     * @param cellName the name of the cell
     * @param cellType the type of the cell
     * @param arguments the cell arguments
     */
    public AbstractCell(String cellName, String cellType, Args arguments)
    {
        super(cellName, cellType, stripDefinedSetup(arguments), false);
        _definedSetup = getDefinedSetup(arguments);
    }

    @Override
    public void cleanUp()
    {
        super.cleanUp();

        if (_timeoutTask != null) {
            _timeoutTask.cancel();
        }
    }


    /**
     * Performs cell initialisation and starts cell message delivery.
     *
     * Initialisation is delegated to the <code>init</code> method,
     * and subclasses should perform initilisation by overriding
     * <code>init</code>. If the <code>init</code> method throws an
     * exception, then the cell is immediately killed.
     *
     * @throws InterruptedException if the thread was interrupted
     * @throws ExecutionException if init threw an exception
     */
    final protected void doInit()
        throws InterruptedException, ExecutionException
    {
        try {
            /* Execute initialisation in a different thread allocated
             * from the correct thread group.
             */
            FutureTask<Void> task = new FutureTask<>(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        parseOptions();

                        _monitor = new MessageProcessingMonitor();
                        _monitor.setCellEndpoint(AbstractCell.this);
                        _monitor.setEnabled(_isMonitoringEnabled);

                        if (_cellClass != null) {
                            getNucleus().setCellClass(_cellClass);
                        }

                        addMessageListener(AbstractCell.this);
                        addCommandListener(_monitor);

                        AbstractCell.this.executeInit();
                        return null;
                    }
                });
            getNucleus().newThread(task, "init").start();
            task.get();

            start();
        } catch (InterruptedException e) {
            LOGGER.info("Cell initialisation was interrupted.");
            start();
            kill();
            throw e;
        } catch (ExecutionException e) {
            start();
            kill();
            throw e;
        } catch (RuntimeException e) {
            // A stacktrace from a RuntimeException is printed in Bootloader
            start();
            kill();
            throw e;
        }
    }

    /**
     * Called from the initialization thread. By default the method
     * first calls the <code>executeDefinedSetup</code> method,
     * followed by the <code>init</code> method and the
     * <code>startTimeoutTask</code> method. Subclasses may override
     * this behaviour if they wish to modify when the defined setup is
     * executed.
     */
    protected void executeInit()
        throws Exception
    {
        executeDefinedSetup();
        init();
        startTimeoutTask();
    }


    /**
     * Start the timeout task.
     *
     * Cells relies on periodic calls to updateWaitQueue to implement
     * message timeouts. This method starts a task which calls
     * updateWaitQueue every 30 seconds.
     */
    protected void startTimeoutTask()
    {
        if (_timeoutTask != null) {
            throw new IllegalStateException("Timeout task is already running");
        }

        final CDC cdc = new CDC();
        _timeoutTask = new TimerTask() {
                @Override
                public void run()
                {
                    try (CDC ignored = cdc.restore()) {
                        getNucleus().updateWaitQueue();
                    } catch (Throwable e) {
                        Thread t = Thread.currentThread();
                        t.getUncaughtExceptionHandler().uncaughtException(t, e);
                    }
                }
            };
        _timer.schedule(_timeoutTask, 30000, 30000);
    }

    /**
     * Executes the defined setup (specified with !variable in the
     * argument string).
     *
     * By default, this method is called from
     * <code>executeInit</code>.
     */
    protected void executeDefinedSetup()
    {
        if (_definedSetup != null) {
            executeDomainContext(_definedSetup);
        }
    }

    /**
     * Initialize cell. This method should be overridden in subclasses
     * to perform cell initialization.
     *
     * The method is called from the <code>executeInit</code> method,
     * but using a thread belonging to the thread group of the
     * associated cell nucleus. This ensure correct logging and
     * correct thread group inheritance.
     *
     * It is valid for the method to call
     * <code>CellAdapter.start</code> if early start of message
     * delivery is needed.
     */
    protected void init() throws Exception {}

    /**
     * Returns the friendly cell name used for logging. It defaults to
     * the cell name.
     */
    protected String getFriendlyName()
    {
        return getCellName();
    }

    public void debug(String str)
    {
        LOGGER.debug(str);
    }

    public void debug(Throwable t)
    {
        LOGGER.debug(t.getMessage());
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        for (String s : sw.toString().split("\n")) {
            LOGGER.debug(s);
        }
    }

    /**
     * Convert an instance to a specific type (kind of intelligent
     * casting).  Note: you can set primitive types as input
     * <i>type</i> but the return type will be the corresponding
     * wrapper type (e.g. Integer.TYPE will result in Integer.class)
     * with the difference that instead of a result 'null' a numeric 0
     * (or boolean false) will be returned because primitive types
     * can't be null.
     *
     * <p>
     * Supported simple destination types are:
     * <ul>
     * <li>java.lang.Boolean, Boolean.TYPE (= boolean.class)
     * <li>java.lang.Byte, Byte.TYPE (= byte.class)
     * <li>java.lang.Character, Character.TYPE (= char.class)
     * <li>java.lang.Double, Double.TYPE (= double.class)
     * <li>java.lang.Float, Float.TYPE (= float.class)
     * <li>java.lang.Integer, Integer.TYPE (= int.class)
     * <li>java.lang.Long, Long.TYPE (= long.class)
     * <li>java.lang.Short, Short.TYPE (= short.class)
     * <li>java.lang.String
     * <li>java.math.BigDecimal
     * <li>java.math.BigInteger
     * </ul>
     *
     * @param object Instance to convert.
     * @param type Destination type (e.g. Boolean.class).
     * @return Converted instance/datatype/collection or null if
     *         input object is null.
     * @throws ClassCastException if <i>object</i> can't be converted to
     *                            <i>type</i>.
     * @author MartinHilpert at SUN's Java Forum
     */
    @SuppressWarnings("unchecked")
    static public <T> T toType(final Object object, final Class<T> type)
    {
        T result = null;

        if (object == null) {
            //initalize primitive types:
            if (type == Boolean.TYPE) {
                result = ((Class<T>) Boolean.class).cast(false);
            } else if (type == Byte.TYPE) {
                result = ((Class<T>) Byte.class).cast(0);
            } else if (type == Character.TYPE) {
                result = ((Class<T>) Character.class).cast(0);
            } else if (type == Double.TYPE) {
                result = ((Class<T>) Double.class).cast(0.0);
            } else if (type == Float.TYPE) {
                result = ((Class<T>) Float.class).cast(0.0);
            } else if (type == Integer.TYPE) {
                result = ((Class<T>) Integer.class).cast(0);
            } else if (type == Long.TYPE) {
                result = ((Class<T>) Long.class).cast(0);
            } else if (type == Short.TYPE) {
                result = ((Class<T>) Short.class).cast(0);
            }
        } else {
            final String so = object.toString();

            //custom type conversions:
            if (type == BigInteger.class) {
                result = type.cast(new BigInteger(so));
            } else if (type == Boolean.class || type == Boolean.TYPE) {
                Boolean r;
                if ("1".equals(so) || "true".equalsIgnoreCase(so) || "yes".equalsIgnoreCase(so) || "on".equalsIgnoreCase(so) || "enabled".equalsIgnoreCase(so)) {
                    r = Boolean.TRUE;
                } else if ("0".equals(object) || "false".equalsIgnoreCase(so) || "no".equalsIgnoreCase(so) || "off".equalsIgnoreCase(so) || "disabled".equalsIgnoreCase(so)) {
                    r = Boolean.FALSE;
                } else {
                    r = Boolean.valueOf(so);
                }

                if (type == Boolean.TYPE) {
                    result = ((Class<T>) Boolean.class).cast(r); //avoid ClassCastException through autoboxing
                } else {
                    result = type.cast(r);
                }
            } else if (type == Byte.class || type == Byte.TYPE) {
                Byte i = Byte.valueOf(so);
                if (type == Byte.TYPE) {
                    result = ((Class<T>) Byte.class).cast(i); //avoid ClassCastException through autoboxing
                } else {
                    result = type.cast(i);
                }
            } else if (type == Character.class || type == Character.TYPE) {
                Character i = so.charAt(0);
                if (type == Character.TYPE) {
                    result = ((Class<T>) Character.class).cast(i); //avoid ClassCastException through autoboxing
                } else {
                    result = type.cast(i);
                }
            } else if (type == Double.class || type == Double.TYPE) {
                Double i = Double.valueOf(so);
                if (type == Double.TYPE) {
                    result = ((Class<T>) Double.class).cast(i); //avoid ClassCastException through autoboxing
                } else {
                    result = type.cast(i);
                }
            } else if (type == Float.class || type == Float.TYPE) {
                Float i = Float.valueOf(so);
                if (type == Float.TYPE) {
                    result = ((Class<T>) Float.class).cast(i); //avoid ClassCastException through autoboxing
                } else {
                    result = type.cast(i);
                }
            } else if (type == Integer.class || type == Integer.TYPE) {
                Integer i = Integer.valueOf(so);
                if (type == Integer.TYPE) {
                    result = ((Class<T>) Integer.class).cast(i); //avoid ClassCastException through autoboxing
                } else {
                    result = type.cast(i);
                }
            } else if (type == Long.class || type == Long.TYPE) {
                Long i = Long.valueOf(so);
                if (type == Long.TYPE) {
                    result = ((Class<T>) Long.class).cast(i); //avoid ClassCastException through autoboxing
                } else {
                    result = type.cast(i);
                }
            } else if (type == Short.class || type == Short.TYPE) {
                Short i = Short.valueOf(so);
                if (type == Short.TYPE) {
                    result = ((Class<T>) Short.class).cast(i); //avoid ClassCastException through autoboxing
                } else {
                    result = type.cast(i);
                }
            } else if (Enum.class.isAssignableFrom(type)) {
                result = type.cast(Enum.valueOf(type.asSubclass(Enum.class), so));
            } else {
                try {
                    Constructor<T> constructor =
                        type.getConstructor(String.class);
                    result = constructor.newInstance(object);
                } catch (NoSuchMethodException e) {
                    //hard cast:
                    result = type.cast(object);
                } catch (SecurityException e) {
                    //hard cast:
                    result = type.cast(object);
                } catch (InstantiationException e) {
                    //hard cast:
                    result = type.cast(object);
                } catch (IllegalAccessException e) {
                    //hard cast:
                    result = type.cast(object);
                } catch (InvocationTargetException e) {
                    //hard cast:
                    result = type.cast(object);
                }
            }
        }

        return result;
    }

    /**
     * Returns the value of an option. If the option is found as a
     * cell argument, the value is taken from there. Otherwise it is
     * taken from the domain context, if found.
     *
     * @param option the option
     * @return the value of the option, or null if the option is
     *         not defined
     * @throws IllegalArgumentException if <code>required</code> is true
     *                                  and the option is not defined.
     */
    protected String getOption(Option option)
    {
        String s;

        s = getArgs().getOpt(option.name());
        if (s != null && (s.length() > 0 || !option.required())) {
            return s;
        }

        s = (String)getDomainContext().get(option.name());
        if (s != null && (s.length() > 0 || !option.required())) {
            return s;
        }

        if (option.required()) {
            throw new IllegalArgumentException(option.name()
                    + " is a required argument");
        }

        return option.defaultValue();
    }

    /**
     * Parses options for this cell.
     *
     * Option parsing is based on <code>Option</code> annotation of
     * fields. This fields must not be class private.
     *
     * Values are logger at the INFO level.
     */
    protected void parseOptions()
    {
        for (Class<?> c = getClass(); c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                Option option = field.getAnnotation(Option.class);
                try {
                    if (option != null) {
                        field.setAccessible(true);

                        String s = getOption(option);
                        Object value;
//                        this filters empty strings with the result that they
//                        become null
                        if (s != null && s.length() > 0) {
                            try {
                                value = toType(s, field.getType());
                                field.set(this, value);
                            } catch (ClassCastException e) {
                                throw new IllegalArgumentException("Cannot convert '" + s + "' to " + field.getType(), e);
                            }
                        } else {
                            value = field.get(this);
                        }

                        if (option.log()) {
                            String description = option.description();
                            String unit = option.unit();
                            if (description.length() == 0) {
                                description = option.name();
                            }
                            if (unit.length() > 0) {
                                LOGGER.info("{} set to {} {}", description, value, unit);
                            } else {
                                LOGGER.info("{} set to {}", description, value);
                            }
                        }
                    }
                } catch (SecurityException | IllegalAccessException e) {
                    throw new RuntimeException("Bug detected while processing option " + option.name(), e);
                }
            }
        }
    }

    /**
     * Writes information about all options (Option annotated fields)
     * to a writer.
     */
    protected void writeOptions(PrintWriter out)
    {
        for (Class<?> c = getClass(); c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                Option option = field.getAnnotation(Option.class);
                try {
                    if (option != null) {
                        if (option.log()) {
                            field.setAccessible(true);
                            Object value = field.get(this);
                            String description = option.description();
                            String unit = option.unit();
                            if (description.length() == 0) {
                                description = option.name();
                            }
                            out.println(description + " is " + value + " " + unit);
                        }
                    }
                } catch (SecurityException | IllegalAccessException e) {
                    throw new RuntimeException("Bug detected while processing option " + option.name(), e);
                }
            }
        }
    }

    /**
     * Adds a listener for dCache messages.
     *
     * @see CellMessageDispatcher#addMessageListener
     */
    public void addMessageListener(CellMessageReceiver o)
    {
        _messageDispatcher.addMessageListener(o);
        _forwardDispatcher.addMessageListener(o);
    }

    /**
     * Removes a listener previously added with addMessageListener.
     */
    public void removeMessageListener(CellMessageReceiver o)
    {
        _messageDispatcher.removeMessageListener(o);
        _forwardDispatcher.removeMessageListener(o);
    }

    /**
     * Sends a reply back to the sender of <code>envelope</code>.
     */
    private void sendReply(CellEndpoint endpoint, CellMessage envelope, Object result)
    {
        Serializable o = envelope.getMessageObject();
        if (o instanceof Message) {
            Message msg = (Message)o;

            /* Don't send reply if not requested. Some vehicles
             * contain a bug in which the message is marked as not
             * requiring a reply, while what was intended was
             * asynchronous processing on the server side. Therefore
             * we have a special test for Reply results.
             */
            if (!msg.getReplyRequired() && !(result instanceof Reply)) {
                return;
            }

            /* dCache vehicles can transport errors back to the
             * requestor, so detect if this is an error reply.
             */
            if (result instanceof CacheException) {
                CacheException e = (CacheException)result;
                msg.setFailed(e.getRc(), e.getMessage());
                result = msg;
            } else if (result instanceof IllegalArgumentException) {
                msg.setFailed(CacheException.INVALID_ARGS,
                              result.toString());
                result = msg;
            } else if (result instanceof Exception) {
                msg.setFailed(CacheException.UNEXPECTED_SYSTEM_EXCEPTION,
                        (Exception) result);
                result = msg;
            }
        }

        try {
            envelope.revertDirection();
            if (result instanceof Reply) {
                Reply reply = (Reply)result;
                reply.deliver(endpoint, envelope);
            } else {
                envelope.setMessageObject((Serializable) result);
                endpoint.sendMessage(envelope);
            }
        } catch (NoRouteToCellException e) {
            LOGGER.error("Cannot deliver reply: No route to " + envelope.getDestinationPath());
        }
    }

    /**
     * Delivers message to registered forward listeners.
     *
     * A reply is delivered back to the client if any message
     * listener:
     *
     * - Returns a value
     *
     * - Throws a checked exception, IllegalStateException or
     *   IllegalArgumentException.
     *
     * dCache vehicles (subclasses of Message) are recognized, and
     * a reply is only sent if requested by the client.
     *
     * For dCache vehicles, errors are reported by sending back the
     * vehicle with an error code. CacheException and
     * IllegalArgumentException are recognised and an appropriate
     * error code is used.
     *
     * Return values implementing Reply are recognized and the reply
     * is delivered by calling the deliver method on the Reply object.
     *
     * If no listener returns a value or throws Throws a checked
     * exception, IllegalStateException or IllegalArgumentException,
     * and the UOID of the envelope is unaltered, then the message is
     * forwarded to the next destination.
     */
    @Override
    public void messageToForward(CellMessage envelope)
    {
        CellEndpoint endpoint = _monitor.getReplyCellEndpoint(envelope);
        UOID uoid = envelope.getUOID();
        CellAddressCore address = envelope.getDestinationPath().getCurrent();
        boolean isReply = isReply(envelope);
        Object result = _forwardDispatcher.call(envelope);

        if (result != null) {
            if (isReply) {
                throw new RuntimeException(String.format(MSG_REPLY_TO_REPLY, result));
            }
            if (!uoid.equals(envelope.getUOID())) {
                throw new RuntimeException(String.format(MSG_UOID_MISMATCH, result));
            }
            if (!address.equals(envelope.getDestinationPath().getCurrent())) {
                throw new RuntimeException(String.format(MSG_ALREADY_FORWARDED, result));
            }
            sendReply(endpoint, envelope, result);
        } else if (address.equals(envelope.getDestinationPath().getCurrent())) {
            if (!envelope.nextDestination()) {
                throw new RuntimeException(String.format(MSG_NO_NEXT_DESTINATION, envelope));
            }
            try {
                endpoint.sendMessage(envelope);
            } catch (NoRouteToCellException e) {
                if (!isReply) {
                    sendReply(this, envelope, e);
                } else {
                    LOGGER.warn("Dropping message: No route to {}",
                            envelope.getDestinationPath());
                }
            }
        }
    }

    private boolean isReply(CellMessage envelope)
    {
        Object message = envelope.getMessageObject();
        return (message instanceof Message) && ((Message) message).isReply();
    }

    /**
     * Delivers messages to registered message listeners.
     *
     * A reply is delivered back to the client if any message
     * listener:
     *
     * - Returns a value
     *
     * - Throws a checked exception, IllegalStateException or
     *   IllegalArgumentException.
     *
     * dCache vehicles (subclasses of Message) are recognized, and
     * a reply is only sent if requested by the client.
     *
     * For dCache vehicles, errors are reported by sending back the
     * vehicle with an error code. CacheException and
     * IllegalArgumentException are recognised and an appropriate
     * error code is used.
     *
     * Return values implementing Reply are recognized and the reply
     * is delivered by calling the deliver method on the Reply object.
     */
    @Override
    public void messageArrived(CellMessage envelope)
    {
        CellEndpoint endpoint = _monitor.getReplyCellEndpoint(envelope);
        UOID uoid = envelope.getUOID();
        boolean isReply = isReply(envelope);
        Object result = _messageDispatcher.call(envelope);

        if (result != null && !isReply) {
            if (!uoid.equals(envelope.getUOID())) {
                throw new RuntimeException(String.format(MSG_UOID_MISMATCH, result));
            }
            sendReply(endpoint, envelope, result);
        }
    }
}
