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

private:
    std::atomic<ThreadState> state_;
};

} // namespace mm
} // namespace kotlin

#endif // RUNTIME_MM_THREAD_SUSPENSION_DATA_H
