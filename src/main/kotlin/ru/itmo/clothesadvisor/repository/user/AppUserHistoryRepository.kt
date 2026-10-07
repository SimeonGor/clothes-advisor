package ru.itmo.clothesadvisor.repository.user

import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.user.AppUserHistory
import ru.itmo.clothesadvisor.model.user.AppUserHistoryId

internal interface AppUserHistoryRepository : Repository<AppUserHistory, AppUserHistoryId> {
    fun save(history: AppUserHistory): AppUserHistory
}
