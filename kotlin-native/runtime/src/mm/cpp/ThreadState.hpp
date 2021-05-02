/*
 * Copyright 2010-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#ifndef RUNTIME_MM_THREAD_STATE_H
#define RUNTIME_MM_THREAD_STATE_H

#include <Common.h>
#include <Utils.hpp>

#include "ThreadData.hpp"
#include "ThreadSuspensionUtils.hpp"

namespace kotlin {

namespace internal {

ALWAYS_INLINE inline bool isStateSwitchAllowed(ThreadState oldState, ThreadState newState, bool reentrant) noexcept  {
    // TODO: May be forbid SUSPEND -> NATIVE switch?
    return oldState != newState || reentrant;
}

const char* stateToString(ThreadState state) noexcept;
std::string statesToString(std::initializer_list<ThreadState> states) noexcept;

} // namespace internal

// Switches the state of the given thread to `newState` and returns the previous thread state.
ALWAYS_INLINE inline ThreadState SwitchThreadState(mm::ThreadData* threadData, ThreadState newState, bool reentrant = false) noexcept {
    // TODO: This change means that state switch is not atomic. Is it ok?
    auto oldState = threadData->state();
    // TODO(perf): Mesaure the impact of this assert in debug and opt modes.
    RuntimeAssert(internal::isStateSwitchAllowed(oldState, newState, reentrant),
                  "Illegal thread state switch. Old state: %s. New state: %s.",
                  internal::stateToString(oldState), internal::stateToString(newState));
    if (oldState == ThreadState::kNative && newState == ThreadState::kRunnable){
        mm::SuspendCurrentThreadIfRequested();
    }
    threadData->setState(newState);
    return oldState;
}

// Asserts that the given thread is in the given state.
ALWAYS_INLINE inline void AssertThreadState(mm::ThreadData* threadData, ThreadState expected) noexcept {
    auto actual = threadData->state();
    RuntimeAssert(actual == expected,
                  "Unexpected thread state. Expected: %s. Actual: %s.",
                  internal::stateToString(expected), internal::stateToString(actual));
}

ALWAYS_INLINE inline void AssertThreadState(mm::ThreadData* threadData, std::initializer_list<ThreadState> expected) noexcept {
    auto actual = threadData->state();
    bool expectedContainsActual = false;
    for (auto state : expected) {
        if (state == actual) {
            expectedContainsActual = true;
            break;
        }
    }
    RuntimeAssert(expectedContainsActual,
                  "Unexpected thread state. Expected one of: %s. Actual: %s",
                  internal::statesToString(expected).c_str(), internal::stateToString(actual));
}

} // namespace kotlin

#endif // RUNTIME_MM_THREAD_STATE_H