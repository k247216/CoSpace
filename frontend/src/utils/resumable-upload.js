// 每个文件最多同时发送三个分片；同编号的重试由后端做幂等校验。
const CHUNK_CONCURRENCY = 3

function newRequestId() {
    const bytes = new Uint8Array(16)
    globalThis.crypto.getRandomValues(bytes)
    bytes[6] = (bytes[6] & 15) | 64
    bytes[8] = (bytes[8] & 63) | 128
    const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
    return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join(
        '-',
    )
}

export async function uploadFile(item, { api, store, hashFile, update }) {
    update({ stage: '准备文件', status: undefined, error: '' })
    if (!item.md5) item.md5 = await hashFile(item.file)
    const input = { name: item.file.name, size: item.file.size, md5: item.md5, parentId: item.parentId }
    const key = JSON.stringify(input)
    let task
    const pending = item.uploadId || store.get(key)
    if (pending) {
        update({ stage: '恢复上传进度' })
        try {
            task = (await api.status(pending)).data
        } catch (error) {
            if (error.status !== 1 || !['上传任务不存在', '上传任务已过期'].includes(error.message))
                throw error
            store.remove(key)
            item.uploadId = null
            item.requestId = null
        }
        if (
            task &&
            (task.state === 'EXPIRED' ||
                (task.state === 'UPLOADING' && new Date(task.expiresAt).getTime() <= Date.now()))
        ) {
            store.remove(key)
            item.uploadId = null
            item.requestId = null
            task = null
        }
        if (task && task.state === 'FAILED') {
            store.remove(key)
            item.uploadId = null
            item.requestId = null
            throw new Error(task.lastError || '文件校验失败，请重试重新上传')
        }
    }
    // 先恢复旧任务，再尝试秒传，避免“完成响应丢失”后新增第二条引用。
    if (!task) {
        // 初始化响应丢失时先重放同一个requestId，避免新建任务或秒传留下旧预占。
        const previousRequest = item.requestId || (store.getRequest && store.getRequest(key))
        if (!previousRequest) {
            update({ stage: '正在检查文件' })
            try {
                await api.instant(input)
                update({ stage: '上传完成', percent: 100, status: 'success', done: true })
                return
            } catch (error) {
                if (error.status !== 1 || error.message !== '秒传未命中，请上传文件') throw error
            }
        }
        update({ stage: '准备上传' })
        item.requestId = previousRequest || newRequestId()
        if (store.setRequest) store.setRequest(key, item.requestId)
        task = (await api.initialize({ ...input, requestId: item.requestId })).data
        item.uploadId = task.uploadId
        store.set(key, task.uploadId)
    }
    if (task.state === 'UPLOADING') {
        const received = new Set(task.uploadedChunks || [])
        const pending = []
        for (let index = 0; index < task.totalChunks; index++) {
            if (!received.has(index)) pending.push(index)
        }
        const inFlight = new Map()
        const sendChunk = task.directUpload ? api.directChunk : api.chunk
        const partBytes = (index) =>
            Math.min(task.chunkSize, item.file.size - index * task.chunkSize) || 1
        const totalBytes = item.file.size || 1
        let receivedBytes = [...received].reduce((sum, index) => sum + partBytes(index), 0)
        const report = () => {
            const partial = Array.from(inFlight.values()).reduce((sum, value) => sum + value, 0)
            update({
                stage: '正在上传',
                percent: Math.min(95, Math.floor(((receivedBytes + partial) / totalBytes) * 95)),
            })
        }
        let cursor = 0,
            failed = false,
            failure
        const worker = async () => {
            while (!failed && !item.cancelRequested && cursor < pending.length) {
                const index = pending[cursor++]
                const start = index * task.chunkSize
                const blob = item.file.slice(start, Math.min(start + task.chunkSize, item.file.size))
                inFlight.set(index, 0)
                try {
                    await sendChunk(task.uploadId, index, blob, (event) => {
                        const current = event.total
                            ? partBytes(index) * Math.max(0, Math.min(1, event.loaded / event.total))
                            : 0
                        inFlight.set(index, Math.max(inFlight.get(index), current))
                        report()
                    })
                    received.add(index)
                    receivedBytes += partBytes(index)
                    inFlight.delete(index)
                    report()
                } catch (error) {
                    if (!failed) {
                        failed = true
                        failure = error
                    }
                }
            }
        }
        report()
        // 出错后停止派发新片，并等待已发出的请求结束，再交给用户重试。
        await Promise.all(
            Array.from({ length: Math.min(CHUNK_CONCURRENCY, pending.length) }, () => worker()),
        )
        if (item.cancelRequested) {
            await api.cancel(task.uploadId)
            store.remove(key)
            item.uploadId = null
            item.requestId = null
            update({ stage: '已取消，预占空间已释放', cancelled: true, error: '', status: 'exception' })
            return
        }
        if (failed) throw failure
    }
    if (item.cancelRequested && task.state === 'FINALIZING') {
        await api.cancel(task.uploadId)
        store.remove(key)
        item.uploadId = null
        item.requestId = null
        update({ stage: '已取消，预占空间已释放', cancelled: true, error: '', status: 'exception' })
        return
    }
    if (task.state !== 'COMPLETED') {
        if (!['UPLOADING', 'FINALIZING'].includes(task.state)) throw new Error('上传任务状态不可用')
        update({ stage: '正在处理文件', percent: 99 })
        task = (await api.complete(task.uploadId)).data
        if (task.state !== 'COMPLETED') throw new Error('文件尚未完成，请重试')
    }
    store.remove(key)
    item.uploadId = null
    item.requestId = null
    update({ stage: '上传完成', percent: 100, status: 'success', done: true })
}
