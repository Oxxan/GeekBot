package be.hehehe.geekbot.bot;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.beanutils.BeanToPropertyValueTransformer;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.webapp.WebAppContext;
import org.jibble.pircbot.IrcException;
import org.jibble.pircbot.NickAlreadyInUseException;
import org.jibble.pircbot.PircBot;
import org.jibble.pircbot.User;

import be.hehehe.geekbot.annotations.RandomAction;
import be.hehehe.geekbot.annotations.ServletMethod;
import be.hehehe.geekbot.annotations.TimedAction;
import be.hehehe.geekbot.annotations.Trigger;
import be.hehehe.geekbot.annotations.TriggerType;
import be.hehehe.geekbot.annotations.Triggers;
import be.hehehe.geekbot.utils.BotUtilsService;
import be.hehehe.geekbot.utils.BundleService;
import be.hehehe.geekbot.utils.LOG;

@Singleton
public class GeekBot extends PircBot {

	private String botName;

	private String channel;
	private List<Method> triggers;
	private List<Method> randoms;

	@Inject
	GeekBotCDIExtension extension;

	@Inject
	BundleService bundleService;

	@Inject
	BotUtilsService utilsService;

	@Inject
	Instance<Object> container;

	private ScheduledExecutorService scheduler;
	private ExecutorService executor;

	@PostConstruct
	public void init() {

		executor = Executors.newCachedThreadPool();
		scheduler = Executors.newScheduledThreadPool(1);

		botName = bundleService.getBotName();
		channel = bundleService.getChannel();
		String server = bundleService.getServer();
		try {

			// scan for commands
			triggers = extension.getTriggers();
			randoms = extension.getRandoms();
			startTimers(extension.getTimers());

			startChangeNickThread();
			startHTTPServer();

			// set parameters and connect to IRC
			this.setMessageDelay(2000);
			this.setName(botName);
			this.setLogin(botName);
			this.setVerbose(true);
			this.setAutoNickChange(true);
			this.setEncoding("ISO-8859-1");
			this.setFinger(botName);
			this.startIdentServer();
			this.connect(server);
			this.joinChannel(channel);

		} catch (Exception e) {
			LOG.handle(e);
		}
	}

	private void startHTTPServer() throws Exception {
		final List<Method> servletMethods = extension.getServletMethods();
		if (!servletMethods.isEmpty()) {

			Server server = new Server(bundleService.getWebServerPort());
			HandlerList handlerList = new HandlerList();

			WebAppContext webappcontext = new WebAppContext();
			webappcontext.setContextPath("/");
			webappcontext.setWar(getClass().getResource("/web")
					.toExternalForm());
			webappcontext.setInitParameter(
					"org.eclipse.jetty.servlet.Default.dirAllowed", "false");
			webappcontext.setErrorHandler(new ErrorPageErrorHandler() {
				@Override
				public void handle(String arg0, Request arg1,
						HttpServletRequest arg2, HttpServletResponse arg3)
						throws IOException {
				}
			});

			for (Method m : servletMethods) {
				String path = m.getAnnotation(ServletMethod.class).value();
				if (StringUtils.isNotBlank(path)) {
					if (!path.startsWith("/")) {
						path = "/" + path;
					}
					webappcontext.addServlet(new ServletHolder(
							new BotMethodServlet(m)), path);
				}
			}
			handlerList.setHandlers(new Handler[] { webappcontext,
					new DefaultHandler() });
			server.setHandler(handlerList);
			server.start();
		}

	}

	private void startTimers(List<Method> timers) {
		for (final Method method : timers) {
			int interval = method.getAnnotation(TimedAction.class).value();
			TimeUnit timeUnit = method.getAnnotation(TimedAction.class)
					.timeUnit();
			Runnable thread = new Runnable() {
				@Override
				public void run() {
					try {
						invokeTrigger(method, buildEvent());
					} catch (Exception e) {
						LOG.handle(e);
					}
				}
			};
			scheduler.scheduleAtFixedRate(thread, 60,
					timeUnit.toSeconds(interval), TimeUnit.SECONDS);
		}
	}

	private void startChangeNickThread() {
		Runnable thread = new Runnable() {
			@Override
			public void run() {
				if (!StringUtils.equals(botName, getNick())) {
					changeNick(botName);
				}
			}
		};
		scheduler.scheduleAtFixedRate(thread, 1, 1, TimeUnit.MINUTES);
	}

	@Override
	protected void onMessage(String channel, String sender, String login,
			String hostname, String message) {

		handlePossibleTrigger(message, sender);
	}

	@Override
	protected void onDisconnect() {
		try {
			while (!isConnected()) {
				this.reconnect();
				this.joinChannel(channel);
			}
		} catch (NickAlreadyInUseException e) {
			LOG.error("Nick already in use!");
		} catch (IOException e) {
			LOG.handle(e);
		} catch (IrcException e) {
			LOG.handle(e);
		}
	}

	/**
	 * Try to rejoin channel when kicked
	 */
	@Override
	protected void onKick(String channel, String kickerNick,
			String kickerLogin, String kickerHostname, String recipientNick,
			String reason) {
		if (recipientNick.equals(botName)) {
			this.joinChannel(channel);
		}
	}

	/**
	 * Handle each message and check for an action
	 * 
	 * @param message
	 * @param author
	 */
	private void handlePossibleTrigger(String message, String author) {

		for (Method triggerMethod : triggers) {
			Trigger trig = triggerMethod.getAnnotation(Trigger.class);
			String trigger = trig.value();
			String triggerStartsWith = trigger + " ";

			TriggerEvent triggerEvent = null;
			switch (trig.type()) {

			case EXACTMATCH:
				if (StringUtils.equals(trigger, message)) {
					triggerEvent = buildEvent(message, author, trigger);
				}
				break;
			case STARTSWITH:
				if (StringUtils.startsWith(message, triggerStartsWith)) {
					triggerEvent = buildEvent(message, author,
							triggerStartsWith);
				}
				break;
			case BOTNAME:
				if (botNameInMessage(message)) {
					triggerEvent = buildEvent(message, author, botName);
				}
				break;

			case EVERYTHING:
				if (!isMessageTrigger(message) && !botNameInMessage(message)) {
					triggerEvent = buildEvent(message, author, null);
				}
				break;
			}

			invokeTrigger(triggerMethod, triggerEvent);
		}

		if (!isMessageTrigger(message)) {
			// this message is not a trigger, try to proc something randomly
			for (Method random : randoms) {
				int rand = new Random().nextInt(100) + 1;
				int probability = random.getAnnotation(RandomAction.class)
						.value();
				if (rand <= probability) {
					invokeTrigger(random, buildEvent(message, author, null));
					break;
				}
			}
		}

	}

	/**
	 * 
	 * @param message
	 * @return true if the message is a trigger
	 */
	private boolean isMessageTrigger(String message) {
		boolean isTrigger = false;
		for (Method triggerMethod : triggers) {
			Trigger trig = triggerMethod.getAnnotation(Trigger.class);
			String trigger = trig.value();
			TriggerType type = trig.type();
			if (type == TriggerType.EXACTMATCH
					|| type == TriggerType.STARTSWITH) {
				if (StringUtils.startsWithIgnoreCase(message, trigger)) {
					isTrigger = true;
					break;
				}
			}
		}
		return isTrigger;
	}

	/**
	 * 
	 * @param message
	 * @return true if the name of the bot is in the message
	 */
	private boolean botNameInMessage(String message) {
		return StringUtils.containsIgnoreCase(message, botName);
	}

	/**
	 * 
	 * @param message
	 * @return true if the msssage contains the nickname of a user on the chan
	 */
	private boolean nickInMessage(String message) {
		boolean nickInMessage = false;
		if (message != null) {
			String[] split = message.split(" ");
			for (User user : getUsers(channel)) {
				for (String token : split) {
					if (token.equalsIgnoreCase(user.getNick())
							|| token.equalsIgnoreCase(user.getNick() + ":")) {
						nickInMessage = true;
						break;
					}
				}
			}
		}
		return nickInMessage;
	}

	private void invokeTrigger(final Method method, final TriggerEvent event) {
		if (event == null) {
			return;
		}
		Runnable runnable = new Runnable() {
			@Override
			public void run() {
				invoke(method, event);
			}
		};
		executor.submit(runnable);

	}

	private void invoke(Method method, Object... args) {

		LOG.debug("Invoking: " + method.getDeclaringClass().getSimpleName()
				+ "#" + method.getName());

		final Object commandInstance = container.select(
				method.getDeclaringClass()).get();

		try {
			Object result = null;
			if (method.getParameterTypes().length == 0) {
				result = method.invoke(commandInstance, new Object[0]);
			} else {
				result = method.invoke(commandInstance, args);
			}
			handleResultOfInvoke(result);
		} catch (Exception e) {
			LOG.handle(e);
		}
	}

	private TriggerEvent buildEvent() {
		return buildEvent(null, null, null);
	}

	@SuppressWarnings("unchecked")
	private TriggerEvent buildEvent(String message, String author,
			String trigger) {
		Collection<String> users = CollectionUtils.collect(
				Arrays.asList(getUsers(channel)),
				new BeanToPropertyValueTransformer("nick"));
		TriggerEvent event = new TriggerEventImpl(message, author, trigger,
				users, utilsService.extractURL(message),
				nickInMessage(message), botNameInMessage(message),
				isMessageTrigger(message), new MessageWriter() {
					@Override
					public void write(String message) {
						sendMessage(message);
					}
				});
		return event;
	}

	/**
	 * Handle the result of the trigger invokation
	 * 
	 */
	@SuppressWarnings("unchecked")
	private void handleResultOfInvoke(Object o) {
		if (o != null) {
			if (o instanceof String) {
				String message = (String) o;
				sendMessage(message);
			} else if (o instanceof List<?>) {
				List<String> messages = (List<String>) o;
				sendMessages(messages);
			}
		}
	}

	private void sendMessages(List<String> list) {
		for (String s : list) {
			sendMessage(s);
		}
	}

	/**
	 * Sends the message, splitting it if too long.
	 * 
	 * @param message
	 */
	private void sendMessage(String message) {
		int limit = 400;
		while (message.length() > limit) {
			int lastSpace = message.substring(0, limit).lastIndexOf(' ');
			sendMessage(channel, message.substring(0, lastSpace));
			message = message.substring(lastSpace + 1);
		}
		sendMessage(channel, message);
	}

	@Produces
	@Triggers
	public List<Method> getTriggers() {
		return triggers;
	}

	@SuppressWarnings("serial")
	private class BotMethodServlet extends HttpServlet {

		private Method method;

		public BotMethodServlet(Method method) {
			this.method = method;
		}

		@Override
		protected void doGet(HttpServletRequest req, HttpServletResponse resp)
				throws ServletException, IOException {
			ServletEvent event = new ServletEventImpl(req, resp);
			invoke(method, event);
		}
	}

}
