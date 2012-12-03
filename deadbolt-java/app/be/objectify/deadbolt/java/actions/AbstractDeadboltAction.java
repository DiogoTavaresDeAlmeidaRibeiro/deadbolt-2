/*
 * Copyright 2010-2012 Steve Chaloner
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package be.objectify.deadbolt.java.actions;

import be.objectify.deadbolt.java.JavaDeadboltAnalyzer;
import be.objectify.deadbolt.java.DeadboltHandler;
import be.objectify.deadbolt.core.models.RoleHolder;
import be.objectify.deadbolt.java.utils.PluginUtils;
import be.objectify.deadbolt.java.utils.ReflectionUtils;
import be.objectify.deadbolt.java.utils.RequestUtils;
import play.Logger;
import play.mvc.Action;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;

/**
 * Provides some convenience methods for concrete Deadbolt actions, such as getting the correct {@link DeadboltHandler},
 * etc.  Extend this if you want to save some time if you create your own action.
 *
 * @author Steve Chaloner (steve@objectify.be)
 */
public abstract class AbstractDeadboltAction<T> extends Action<T>
{
    private static final String ACTION_AUTHORISED = "deadbolt.action-authorised";

    private static final String ACTION_UNAUTHORISED = "deadbolt.action-unauthorised";

    private static final String ACTION_DEFERRED = "deadbolt.action-deferred";
    private static final String IGNORE_DEFERRED_FLAG = "deadbolt.ignore-deferred-flag";

    /**
     * Gets the current {@link DeadboltHandler}.  This can come from one of two places:
     * - a class name is provided in the annotation.  A new instance of that class will be created. This has the highest priority.
     * - the global handler defined in the application.conf by deadbolt.handler
     *
     * @param deadboltHandlerClass the DeadboltHandler class, if any, coming from the annotation. May be null.
     * @param <C>                  the actual class of the DeadboltHandler
     * @return an instance of DeadboltHandler.
     */
    protected <C extends DeadboltHandler> DeadboltHandler getDeadboltHandler(Class<C> deadboltHandlerClass) throws
                                                                                                            Throwable
    {
        DeadboltHandler deadboltHandler;
        if (deadboltHandlerClass != null
            && !deadboltHandlerClass.isInterface())
        {
            try
            {
                deadboltHandler = deadboltHandlerClass.newInstance();
            }
            catch (Exception e)
            {
                throw new RuntimeException("Error creating Deadbolt handler",
                                           e);
            }
        }
        else
        {
            deadboltHandler = PluginUtils.getDeadboltHandler();
        }
        return deadboltHandler;
    }

    /** {@inheritDoc} */
    @Override
    public Result call(Http.Context ctx) throws Throwable
    {
        Result result;

        Class annClass = configuration.getClass();
        if (isDeferred(ctx))
        {
            result = getDeferredAction(ctx).call(ctx);
        }
        else if (!ctx.args.containsKey(IGNORE_DEFERRED_FLAG)
                && ReflectionUtils.hasMethod(annClass, "deferred") &&
                (Boolean)annClass.getMethod("deferred").invoke(configuration))
        {
            defer(ctx,
                  this);
            result = delegate.call(ctx);
        }
        else
        {
            result = execute(ctx);
        }
        return result;
    }

    /**
     * Execute the action.
     *
     * @param ctx the request context
     * @return the result
     * @throws Throwable if something bad happens
     */
    public abstract Result execute(Http.Context ctx) throws Throwable;

    /**
     * @param roleHolder
     * @param roleNames
     * @return
     */
    protected boolean checkRole(RoleHolder roleHolder,
                                String[] roleNames)
    {
        return JavaDeadboltAnalyzer.checkRole(roleHolder,
                                              roleNames);
    }

    /**
     * @param roleHolder
     * @param roleNames
     * @return
     */
    protected boolean hasAllRoles(RoleHolder roleHolder,
                                  String[] roleNames)
    {
        return JavaDeadboltAnalyzer.hasAllRoles(roleHolder,
                                                roleNames);
    }

    /**
     * Wrapper for {@link DeadboltHandler#onAccessFailure} to ensure the access failure is logged.
     *
     * @param deadboltHandler the Deadbolt handler
     * @param content         the content type hint
     * @param ctx             th request context
     * @return the result of {@link DeadboltHandler#onAccessFailure}
     */
    protected Result onAccessFailure(DeadboltHandler deadboltHandler,
                                     String content,
                                     Http.Context ctx)
    {
        Logger.warn(String.format("Deadbolt: Access failure on [%s]",
                                  ctx.request().uri()));

        try
        {
            return deadboltHandler.onAccessFailure(ctx,
                                                   content);
        }
        catch (Exception e)
        {
            Logger.warn("Deadbolt: Exception when invoking onAccessFailure",
                        e);
            return Results.internalServerError();
        }
    }

    /**
     * Gets the {@link RoleHolder} from the {@link DeadboltHandler}, and logs an error if it's not present. Note that
     * at least one actions ({@link Unrestricted} does not not require a RoleHolder to be present.
     *
     * @param ctx             the request context
     * @param deadboltHandler the Deadbolt handler
     * @return the RoleHolder, if any
     */
    protected RoleHolder getRoleHolder(Http.Context ctx,
                                       DeadboltHandler deadboltHandler)
    {
        RoleHolder roleHolder = RequestUtils.getRoleHolder(deadboltHandler,
                                                           ctx);
        if (roleHolder == null)
        {
            Logger.error(String.format("Access to [%s] requires a RoleHolder, but no RoleHolder is present.",
                                       ctx.request().uri()));
        }

        return roleHolder;
    }

    /**
     * Marks the current action as authorised.  This allows method-level annotations to override controller-level annotations.
     *
     * @param ctx the request context
     */
    protected void markActionAsAuthorised(Http.Context ctx)
    {
        ctx.args.put(ACTION_AUTHORISED,
                     true);
    }

    /**
     * Marks the current action as unauthorised.  This allows method-level annotations to override controller-level annotations.
     *
     * @param ctx the request context
     */
    protected void markActionAsUnauthorised(Http.Context ctx)
    {
        ctx.args.put(ACTION_UNAUTHORISED,
                     true);
    }

    /**
     * Checks if an action is authorised.  This allows controller-level annotations to cede control to method-level annotations.
     *
     * @param ctx the request context
     * @return true if a more-specific annotation has authorised access, otherwise false
     */
    protected boolean isActionAuthorised(Http.Context ctx)
    {
        Object o = ctx.args.get(ACTION_AUTHORISED);
        return o != null && (Boolean) o;
    }

    /**
     * Checks if an action is unauthorised.  This allows controller-level annotations to cede control to method-level annotations.
     *
     * @param ctx the request context
     * @return true if a more-specific annotation has blocked access, otherwise false
     */
    protected boolean isActionUnauthorised(Http.Context ctx)
    {
        Object o = ctx.args.get(ACTION_UNAUTHORISED);
        return o != null && (Boolean) o;
    }

    /**
     * Defer execution until a later point.
     *
     * @param ctx the request context
     * @param action the action to defer
     */
    protected void defer(Http.Context ctx,
                         AbstractDeadboltAction action)
    {
        if (action != null)
        {
            Logger.info(String.format("Deferring action [%s]",
                                      this.getClass().getName()));
            ctx.args.put(ACTION_DEFERRED,
                         action);
        }
    }

    /**
     * Check if there is a deferred action in the context.
     *
     * @param ctx the request context
     * @return true iff there is a deferred action in the context
     */
    public boolean isDeferred(Http.Context ctx)
    {
        return ctx.args.containsKey(ACTION_DEFERRED);
    }

    /**
     * Get the deferred action from the context.
     *
     * @param ctx the request context
     * @return the deferred action, or null if it doesn't exist
     */
    public AbstractDeadboltAction getDeferredAction(Http.Context ctx)
    {
        AbstractDeadboltAction action = null;
        Object o = ctx.args.get(ACTION_DEFERRED);
        if (o != null)
        {
            action = (AbstractDeadboltAction)o;

            ctx.args.remove(ACTION_DEFERRED);
            ctx.args.put(IGNORE_DEFERRED_FLAG,
                         true);
        }
        return action;
    }
}