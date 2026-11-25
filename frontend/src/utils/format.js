export function formatBytes(value) {
    if (value === null || value === undefined) return '大小未保留'
    if (value < 1024) return value + ' B'
    const units = ['KiB', 'MiB', 'GiB', 'TiB']
    let size = value / 1024,
        index = 0
    while (size >= 1024 && index < units.length - 1) {
        size /= 1024
        index++
    }
    return size.toFixed(size >= 10 ? 1 : 2) + ' ' + units[index]
}
export function formatDate(value) {
    return value
        ? new Date(value).toLocaleString('zh-CN', {
              month: '2-digit',
              day: '2-digit',
              hour: '2-digit',
              minute: '2-digit',
              hour12: false,
          })
        : '—'
}
