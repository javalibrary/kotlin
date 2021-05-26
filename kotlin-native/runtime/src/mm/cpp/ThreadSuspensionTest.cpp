/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#include "MemoryPrivate.hpp"
#include "ThreadSuspension.hpp"
#include "ThreadState.hpp"

#include <gtest/gtest.h>
#include <gmock/gmock.h>

#include <thread>
#include <TestSupport.hpp>

#include <iostream>

using namespace kotlin;

namespace {

constexpr size_t kDefaultIterations = 10000;

void joinAll(KStdVector<std::thread>& threads) {
    for (auto& thread : threads) {
        thread.join();
    }
}

KStdVector<mm::ThreadData*> collectThreadData() {
    KStdVector<mm::ThreadData*> result;
    auto iter = mm::ThreadRegistry::Instance().Iter();
    for (auto& thread : iter) {
        result.push_back(&thread);
    }
    return result;
}

template<typename T, typename F>
KStdVector<T> collectFromThreadData(F extractFunction) {
    KStdVector<T> result;
    auto threadData = collectThreadData();
    std::transform(threadData.begin(), threadData.end(), std::back_inserter(result), extractFunction);
    return result;
}

KStdVector<bool> collectSuspended() {
    return collectFromThreadData<bool>(
            [](mm::ThreadData* threadData) { return threadData->suspensionData().suspended(); });
}

void reportProgress(size_t currentIteration, size_t totalIterations) {
    if (currentIteration % 1000 == 0) {
       std::cout << "Iteration: " << currentIteration << " of " << totalIterations << std::endl;
    }
}

} // namespace

TEST(ThreadSuspensionTest, SimpleStartStop) {
    constexpr size_t kThreadCount = kDefaultThreadCount;
    constexpr size_t kIterations = kDefaultIterations;
    KStdVector<std::thread> threads;
    std::array<std::atomic<bool>, kThreadCount> ready{false};
    std::atomic<bool> canStart(false);
    std::atomic<bool> shouldStop(false);
    ASSERT_THAT(collectThreadData(), testing::IsEmpty());

    for (size_t i = 0; i < kThreadCount; i++) {
        threads.emplace_back([&canStart, &shouldStop, &ready, i]() {
            ScopedMemoryInit init;
            auto& suspensionData = init.memoryState()->GetThreadData()->suspensionData();
            EXPECT_EQ(mm::IsThreadSuspensionRequested(), false);

            while(!shouldStop) {
                ready[i] = true;
                while(!canStart) {
                    std::this_thread::yield();
                }
                ready[i] = false;

                EXPECT_FALSE(suspensionData.suspended());
                suspensionData.suspendIfRequested();
                EXPECT_FALSE(suspensionData.suspended());
           }
        });
    }

    while (!std::all_of(ready.begin(), ready.end(), [](bool it) { return it; })) {
        std::this_thread::yield();
    }

    for (size_t i = 0; i < kIterations; i++) {
        reportProgress(i, kIterations);
        canStart = true;

        mm::SuspendThreads();
        auto suspended = collectSuspended();
        EXPECT_THAT(suspended, testing::Each(true));
        EXPECT_EQ(mm::IsThreadSuspensionRequested(), true);

        mm::ResumeThreads();

        // Wait for threads to run and sync for the next iteration
        canStart = false;
        while (!std::all_of(ready.begin(), ready.end(), [](bool it) { return it; })) {
            std::this_thread::yield();
        }

        suspended = collectSuspended();
        EXPECT_THAT(suspended, testing::Each(false));
        EXPECT_EQ(mm::IsThreadSuspensionRequested(), false);
    }

    canStart = true;
    shouldStop = true;
    joinAll(threads);
}


TEST(ThreadSuspensionTest, SwitchStateToNative) {
    constexpr size_t kThreadCount = kDefaultThreadCount;
    constexpr size_t kIterations = kDefaultIterations;
    KStdVector<std::thread> threads;
    std::array<std::atomic<bool>, kThreadCount> ready{false};
    std::atomic<bool> canStart(false);
    std::atomic<bool> shouldStop(false);
    ASSERT_THAT(collectThreadData(), testing::IsEmpty());

    for (size_t i = 0; i < kThreadCount; i++) {
        threads.emplace_back([&canStart, &shouldStop, &ready, i]() {
            ScopedMemoryInit init;
            auto* threadData = init.memoryState()->GetThreadData();
            EXPECT_EQ(mm::IsThreadSuspensionRequested(), false);

            while(!shouldStop) {
                ready[i] = true;
                while(!canStart) {
                    std::this_thread::yield();
                }
                ready[i] = false;

                EXPECT_EQ(threadData->state(), ThreadState::kRunnable);
                SwitchThreadState(threadData, ThreadState::kNative);
                EXPECT_EQ(threadData->state(), ThreadState::kNative);
                SwitchThreadState(threadData, ThreadState::kRunnable);
                EXPECT_EQ(threadData->state(), ThreadState::kRunnable);
            }
        });
    }

    for (size_t i = 0; i < kIterations; i++) {
        reportProgress(i, kIterations);

        while (!std::all_of(ready.begin(), ready.end(), [](bool it) { return it; })) {
            std::this_thread::yield();
        }
        canStart = true;

        mm::SuspendThreads();
        EXPECT_EQ(mm::IsThreadSuspensionRequested(), true);

        mm::ResumeThreads();
        EXPECT_EQ(mm::IsThreadSuspensionRequested(), false);

        // Sync for the next iteration.
        canStart = false;
    }

    canStart = true;
    shouldStop = true;
    joinAll(threads);
}