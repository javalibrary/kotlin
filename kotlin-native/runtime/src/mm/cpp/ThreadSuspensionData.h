/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#ifndef RUNTIME_MM_THREAD_SUSPENSION_DATA_H
#define RUNTIME_MM_THREAD_SUSPENSION_DATA_H

#include <atomic>

#include "Memory.h"

namespace kotlin {
namespace mm {

class ThreadSuspensionData : Pinned {
public:
    explicit ThreadSuspensionData(ThreadState initialState) noexcept : state_(initialState) {}

    ~ThreadSuspensionData() = default;

    ThreadState state() noexcept { return state_; }

    ThreadState setState(ThreadState state) noexcept { return state_.exchange(state); }

    std::condition_variable& conditionVar() noexcept { return conditionVar_; }

    std::mutex& mutex() noexcept { return mutex_; }

private:
    std::atomic<ThreadState> state_;
    std::condition_variable conditionVar_;
    std::mutex mutex_;
};

} // namespace mm
} // namespace kotlin

#endif // RUNTIME_MM_THREAD_SUSPENSION_DATA_H
