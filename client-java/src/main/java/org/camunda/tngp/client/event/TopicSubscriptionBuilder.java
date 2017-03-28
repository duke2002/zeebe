package org.camunda.tngp.client.event;

/**
 * <p>Builder used to subscribed to all events of any kind of topic. Builds a <code>managed</code> subscription,
 * i.e. where a supplied event handler is invoked whenever events are received.
 *
 * <p>By default, a subscription starts at the current tail of the topic (see {@link #startAtTailOfTopic()}).
 *
 * <p>When an event handler invocation fails, invoking it is retried two times before the subscription is closed.
 */
public interface TopicSubscriptionBuilder
{

    /**
     * Registers a handler that handles all types of events.
     *
     * @param handler the handler to register
     * @return this builder
     */
    TopicSubscriptionBuilder handler(TopicEventHandler handler);

    /**
     * Defines the position at which to start receiving events from.
     * A <code>position</code> greater than the current tail position
     * of the topic is equivalent to starting at the tail position. In this case,
     * events with a lower position than the supplied position may be received.
     *
     * @param position the position in the topic at which to start receiving events from
     * @return this builder
     */
    TopicSubscriptionBuilder startAtPosition(long position);

    /**
     * Same as invoking {@link #startAtPosition(long)} with the topic's current tail position.
     * In particular, it is guaranteed that this subscription does not receive any event that
     * was receivable before this subscription is opened.
     *
     * @return this builder
     */
    TopicSubscriptionBuilder startAtTailOfTopic();

    /**
     * Same as invoking {@link #startAtPosition(long)} with <code>position = 0</code>.
     *
     * @return this builder
     */
    TopicSubscriptionBuilder startAtHeadOfTopic();

    /**
     * <p>Sets the name of a subscription. The name is used by the broker to record and persist the
     * subscription's position. When a subscription is reopened, this state is used to resume
     * the subscription at the previous position. In this case, methods like {@link #startAtPosition(long)}
     * have no effect (the subscription has already started before).
     *
     * <p>Example:
     * <pre>
     * TopicSubscriptionBuilder builder = ...;
     * builder
     *   .startAtPosition(0)
     *   .name("app1")
     *   ...
     *   .open();
     * </pre>
     * When executed the first time, this snippet creates a new subscription beginning at position 0.
     * When executed a second time, this snippet creates a new subscription beginning at the position
     * at which the first subscription left off.
     *
     * <p>This parameter is required.
     *
     * @param name the name of the subscription. must be unique for the addressed topic
     * @return this builder
     */
    TopicSubscriptionBuilder name(String name);


    /**
     * Opens a new topic subscription with the defined parameters.
     *
     * @return a new subscription
     */
    TopicSubscription open();
}
