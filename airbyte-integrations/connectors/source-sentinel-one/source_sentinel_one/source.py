#
# Copyright (c) 2022 Airbyte, Inc., all rights reserved.
#


from abc import ABC
from typing import Any, Iterable, List, Mapping, MutableMapping, Optional, Tuple
from wsgiref import headers

import requests
from airbyte_cdk.sources import AbstractSource
from airbyte_cdk.sources.streams import Stream
from airbyte_cdk.sources.streams.http import HttpStream
from airbyte_cdk.sources.streams.http.auth import TokenAuthenticator



# Basic full refresh stream
class SentinelOneStream(HttpStream, ABC):
    LIMIT = 100
    # TODO: Fill in the url base. Required.
    url_base = "https:"

    def __init__(self, api_token: str, your_management_url :str, **kwargs):
        super().__init__(**kwargs)
        self.api_token = api_token
        self.your_management_url = your_management_url

    def request_headers(self, **kwargs) -> Mapping[str, Any]:
        api_token = self.api_token
        headers = {"Authorization": f"ApiToken {api_token}", "Content-Type": "application/json", "Accept": "application/json"}
        return headers


    def next_page_token(self, response: requests.Response) -> Optional[Mapping[str, Any]]:
        
        results = response.json().get("pagination")
        nextCursor = results.get("nextCursor")
        if nextCursor:
            return nextCursor
        return None

    def request_params(
        self, stream_state: Mapping[str, Any], stream_slice: Mapping[str, any] = None, next_page_token: Mapping[str, Any] = None
    ) -> MutableMapping[str, Any]:

        params = {"limit": self.LIMIT}
        if next_page_token:
            params.update({"cursor": f'{next_page_token}'})
        return params

    def parse_response(self, response: requests.Response, **kwargs) -> Iterable[Mapping]:
        
        response_json = response.json().get("data")
        return response_json


class Activities(SentinelOneStream):
    
    primary_key = None

    def path(
        self, stream_state: Mapping[str, Any] = None, stream_slice: Mapping[str, Any] = None, next_page_token: Mapping[str, Any] = None
    ) -> str:
        url = self.your_management_url
        return f'{url}/web/api/v2.1/activities'

class Accounts(SentinelOneStream):

    primary_key = None

    def path(
        self, stream_state: Mapping[str, Any] = None, stream_slice: Mapping[str, Any] = None, next_page_token: Mapping[str, Any] = None
    ) -> str:
        url = self.your_management_url
        return f'{url}/web/api/v2.1/accounts'

class Agents(SentinelOneStream):
    primary_key = None

    def path(
        self, stream_state: Mapping[str, Any] = None, stream_slice: Mapping[str, Any] = None, next_page_token: Mapping[str, Any] = None
    ) -> str:
        url = self.your_management_url
        return f'{url}/web/api/v2.1/agents'

#Alerts
class Alerts(SentinelOneStream):
    primary_key = None

    def path(
        self, stream_state: Mapping[str, Any] = None, stream_slice: Mapping[str, Any] = None, next_page_token: Mapping[str, Any] = None
    ) -> str:
        url = self.your_management_url
        return f'{url}/web/api/v2.1/cloud-detection/alerts'

#Applications Risk
class Installed_applications(SentinelOneStream):
    primary_key = None

    def path(
        self, stream_state: Mapping[str, Any] = None, stream_slice: Mapping[str, Any] = None, next_page_token: Mapping[str, Any] = None
    ) -> str:
        url = self.your_management_url
        return f'{url}/web/api/v2.1/installed-applications'

class Installed_applications_cves(SentinelOneStream):
    primary_key = None

    def path(
        self, stream_state: Mapping[str, Any] = None, stream_slice: Mapping[str, Any] = None, next_page_token: Mapping[str, Any] = None
    ) -> str:
        url = self.your_management_url
        return f'{url}/web/api/v2.1/installed-applications/cves'


class Config_override(SentinelOneStream):
    primary_key = None

    def path(
        self, stream_state: Mapping[str, Any] = None, stream_slice: Mapping[str, Any] = None, next_page_token: Mapping[str, Any] = None
    ) -> str:
        url = self.your_management_url
        return f'{url}/web/api/v2.1/config-override'

# Source
class SourceSentinelOne(AbstractSource):
    def check_connection(self, logger, config) -> Tuple[bool, any]:
        api_token = config["api_token"]
        headers = {"Authorization": f"ApiToken {api_token}", "Content-Type": "application/json", "Accept": "application/json"}
        url = "https://usea1-300-nfr.sentinelone.net/web/api/v2.1/activities"

        try:
            session = requests.get(url, headers=headers)
            session.raise_for_status()
            return True, None
        except requests.exceptions.RequestException as e:
            return False, e

    def streams(self, config: Mapping[str, Any]) -> List[Stream]:
        args = {"api_token": config["api_token"],
                "your_management_url": config["your_management_url"]}
        return [Activities(**args),
                Accounts(**args),
                Agents(**args),
                Config_override(**args),
                Installed_applications(**args),
                Installed_applications_cves(**args),
                Alerts(**args)]
        