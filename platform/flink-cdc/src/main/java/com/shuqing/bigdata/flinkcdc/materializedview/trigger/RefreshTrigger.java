package com.shuqing.bigdata.flinkcdc.materializedview.trigger;

import java.util.function.Consumer;

/**
 * 物化视图刷新触发器接口。
 *
 * <p>不同实现代表不同的触发来源：CDC 变更、定时调度、手动 API。
 * 所有触发器在触发时通过回调 {@link #trigger(RefreshEvent)} 将事件
 * 传递给注册的 {@link Consumer}（通常是 ViewRefresher）。</p>
 *
 * @author shuqing-bigdata
 */
public interface RefreshTrigger {

    /**
     * 注册事件处理器（通常为 ViewRefresher）。
     *
     * @param handler 事件处理回调
     */
    void registerHandler(Consumer<RefreshEvent> handler);

    /**
     * 启动触发器。
     */
    void start();

    /**
     * 停止触发器。
     */
    void stop();

    /**
     * 判断触发器是否正在运行。
     *
     * @return 若正在运行返回 true
     */
    boolean isRunning();
}