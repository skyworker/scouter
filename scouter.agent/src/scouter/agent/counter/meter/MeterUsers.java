/*
 *  Copyright 2015 LG CNS.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); 
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License. 
 */
package scouter.agent.counter.meter;

import java.util.Enumeration;
import scouter.agent.Logger;
import scouter.util.DateUtil;
import scouter.util.LongLongLinkedMap;

public class MeterUsers {

	private static final int MAX_USERS = 70000;
	protected static LongLongLinkedMap users = new LongLongLinkedMap().setMax(MAX_USERS);
	protected static MeterResource newUsers = new MeterResource();

	public static void add(long userid) {
		if (userid == 0)
			newUsers.add(1);
		else {
			users.putLast(userid, System.currentTimeMillis());
		}
	}

	public synchronized static int getUsers() {
		int v = 0;
		long now = System.currentTimeMillis();
		try {
			Enumeration<LongLongLinkedMap.ENTRY> en = users.entries();
			while (en.hasMoreElements()) {
				LongLongLinkedMap.ENTRY e = en.nextElement();
				if (now - e.getValue() > DateUtil.MILLIS_PER_FIVE_MINUTE) {
				   users.remove(e.getKey());
				} else {
					v++;
				}
			}
		} catch (Throwable t) {
			Logger.println("A114", "METER-USERS" + t.toString());
		}
		return v;
	}

	public synchronized static int getNewUsers() {
		return (int) newUsers.getSum(300);
	}

	public static void main(String[] args) throws InterruptedException {
		for (int i = 1; i <= 100; i++) {
			if(i==50)
				Thread.sleep(1000);
			add(i);
		}
		Thread.sleep(1000);
		System.out.println(getUsers());
	}
}
