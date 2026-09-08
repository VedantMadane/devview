package com.worldline.devview.networkmock.fixtures

import com.worldline.devview.networkmock.core.model.Operation
import com.worldline.devview.networkmock.core.model.OperationDescriptor
import com.worldline.devview.networkmock.core.model.OperationKey
import com.worldline.devview.networkmock.core.model.OperationMockState
import com.worldline.devview.networkmock.model.ApiSpecUiModel
import com.worldline.devview.networkmock.model.OperationUiModel
import com.worldline.devview.networkmock.viewmodel.NetworkMockUiState
import kotlinx.collections.immutable.persistentListOf

internal object MockScreenTestData {

    private fun spec(specId: String, name: String): ApiSpecUiModel = ApiSpecUiModel(
        specId = specId,
        name = name,
        operations = persistentListOf(
            OperationUiModel(
                descriptor = OperationDescriptor(
                    key = OperationKey(specId = specId, operationId = "getUser"),
                    config = Operation(
                        operationId = "getUser",
                        name = "Get User",
                        path = "/api/users/{userId}",
                        method = "GET"
                    )
                ),
                currentState = OperationMockState.Network
            ),
            OperationUiModel(
                descriptor = OperationDescriptor(
                    key = OperationKey(specId = specId, operationId = "createUser"),
                    config = Operation(
                        operationId = "createUser",
                        name = "Create User",
                        path = "/api/users",
                        method = "POST"
                    )
                ),
                currentState = OperationMockState.Mock(statusCode = 201, exampleName = "default")
            )
        )
    )

    fun contentState(globalMockingEnabled: Boolean = false): NetworkMockUiState.Content =
        NetworkMockUiState.Content(
            globalMockingEnabled = globalMockingEnabled,
            specs = persistentListOf(
                spec(specId = "example", name = "Example"),
                spec(specId = "catalog", name = "Catalog")
            )
        )
}
